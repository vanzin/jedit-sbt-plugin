//            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
//                    Version 2, December 2004
//
// Copyright (C) 2014 Marcelo Vanzin <vanza@users.sourceforge.net>
//
// Everyone is permitted to copy and distribute verbatim or modified
// copies of this license document, and changing it is allowed as long
// as the name is changed.
//
//            DO WHAT THE FUCK YOU WANT TO PUBLIC LICENSE
//   TERMS AND CONDITIONS FOR COPYING, DISTRIBUTION AND MODIFICATION
//
//  0. You just DO WHAT THE FUCK YOU WANT TO.
package sbtplugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import javax.swing.text.DefaultStyledDocument;
import javax.swing.text.Segment;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;

import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.EditBus;
import org.gjt.sp.jedit.EditBus.EBHandler;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.gui.HistoryTextField;
import org.gjt.sp.jedit.msg.PropertiesChanged;
import org.gjt.sp.util.Log;

import common.io.ProcessExecutor;
import common.io.ProcessExecutor.Visitor;
import errorlist.DefaultErrorSource;
import errorlist.DefaultErrorSource.DefaultError;
import errorlist.ErrorSource;
import projectviewer.ProjectViewer;
import projectviewer.event.ViewerUpdate;
import projectviewer.vpt.VPTProject;

public class SbtConsole extends JPanel {

  private static final String HISTORY_KEY =
      SbtConsole.class.getName() + ".commands";
  private static final String SBT_DISABLED = "sbt.disabled.msg";

  private static final Pattern ERROR =
      Pattern.compile("\\s*\\[error\\]\\s+(.+?):([0-9]+):\\s+(.*)[\r\n]+");
  private static final Pattern WARNING =
      Pattern.compile("\\s*\\[warn\\]\\s+(.+?):([0-9]+):\\s+(.*)[\r\n]+");
  private static final Pattern EXTRA =
      Pattern.compile("\\s*\\[(?:error|warn)\\]\\s{2}(.+)[\r\n]+");
  private static final Pattern GENERIC_ERROR =
      Pattern.compile("\\s*\\[error\\].*[\r\n]+");
  private static final Pattern GENERIC_WARNING =
      Pattern.compile("\\s*\\[warn\\].*[\r\n]+");

  private static final Pattern WAITING =
      Pattern.compile("[0-9]+\\. Waiting for source changes\\.\\.\\..*[\r\n]+");

  private final HistoryTextField entry;
  private final JTextPane console;
  private final DefaultStyledDocument document;
  private final View view;

  private Color plainColor;
  private Color infoColor;
  private Color warningColor;
  private Color errorColor;

  private SbtHandler handler;
  private ProcessExecutor sbt;
  private OutputStream stdin;
  private int bufferLineCount;
  private int maxScrollback;
  private ExecutorService streams;

  public SbtConsole(View view) {
    super(new BorderLayout());
    this.view = view;

    JPanel entryPanel = new JPanel(new BorderLayout());
    entryPanel.add(BorderLayout.WEST,
        new JLabel(jEdit.getProperty("sbtplugin.shell.entry")));

    entry = new HistoryTextField(HISTORY_KEY);
    entry.setEnterAddsToHistory();
    entry.addActionListener(new CommandRunner());
    entryPanel.add(BorderLayout.CENTER, entry);
    add(BorderLayout.NORTH, entryPanel);

    document = new DefaultStyledDocument();
    console = new JTextPane(document);
    console.setEditable(false);
    add(BorderLayout.CENTER, new JScrollPane(console));
    propertiesChanged(null);
    EditBus.addToBus(this);

    startSbt(ProjectViewer.getActiveProject(view));
  }

  @EBHandler
  public void propertiesChanged(PropertiesChanged unused)
  {
    if (jEdit.getBooleanProperty("textColors")) {
      plainColor = jEdit.getColorProperty("view.fgColor", Color.BLACK);
      console.setBackground(jEdit.getColorProperty("view.bgColor", Color.WHITE));
      console.setCaretColor(jEdit.getColorProperty("view.caretColor", plainColor));
    } else {
      plainColor = jEdit.getColorProperty("console.plainColor", Color.BLACK);
      console.setBackground(jEdit.getColorProperty("console.bgColor", Color.WHITE));
      console.setCaretColor(jEdit.getColorProperty("console.caretColor", plainColor));
    }
    console.setForeground(plainColor);
    console.setFont(jEdit.getFontProperty("console.font"));
    infoColor = jEdit.getColorProperty("console.infoColor");
    warningColor = jEdit.getColorProperty("console.warningColor");
    errorColor = jEdit.getColorProperty("console.errorColor");
    maxScrollback = SbtGlobalOptions.getMaxScrollback();
  }

  @EBHandler
  public void projectViewerUpdate(ViewerUpdate update) {
    if (update.getView() != view) {
      return;
    }

    stopSbt();

    if (update.getType() != ViewerUpdate.Type.PROJECT_LOADED) {
      return;
    }

    startSbt((VPTProject) update.getNode());
  }

  private void startSbt(final VPTProject project) {
    if (project == null || !isEnabled(project)) {
      try {
        document.insertString(0, jEdit.getProperty(SBT_DISABLED),
            new SimpleAttributeSet());
      } catch (BadLocationException ble) {
        ble.printStackTrace();
      }
      return;
    }

    this.streams = Executors.newFixedThreadPool(2,
      new ThreadFactory() {
        private final AtomicInteger id = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
          Thread t = new Thread(r);
          t.setName(String.format("SBT-%s-%d", project.getName(),
              id.incrementAndGet()));
          return t;
        }
      });

    ProcessExecutor pe = new ProcessExecutor(
        SbtGlobalOptions.getSbtCommand(),
        "-Dsbt.log.noformat=true");
    pe.setExecutor(streams);
    pe.addCurrentEnv();
    for (SbtOptionsPane.EnvVar v : SbtOptionsPane.getEnv(project)) {
      pe.addEnv(v.name, v.value);
    }
    pe.setDirectory(project.getRootPath());

    this.handler = new SbtHandler(SbtOptionsPane.getMonitorCmd(project));
    pe.addVisitor(handler);

    try {
      Process p = pe.start();
      stdin = p.getOutputStream();
    } catch (IOException ioe) {
      JOptionPane.showMessageDialog(view,
          jEdit.getProperty("sbt.error.child",
                            new Object[] { ioe.getMessage() }));
      streams.shutdownNow();
      streams = null;
      return;
    }
    this.sbt = pe;
  }

  private void stopSbt() {
    if (sbt != null) {
      handler.runCommand("exit");
      try {
        sbt.waitFor();
      } catch (InterruptedException ie) {
        // Nothing to do.
      }
      streams.shutdown();
    }
    try {
      document.remove(0, document.getLength());
    } catch (BadLocationException ble) {
      ble.printStackTrace();
    }
    bufferLineCount = 0;

    this.sbt = null;
    this.handler = null;
    this.streams = null;
  }

  private boolean isEnabled(VPTProject p) {
    return SbtOptionsPane.getBoolean(p, SbtOptionsPane.SBT_ENABLED);
  }

  private void appendToConsole(String line, Color color) {
    try {
      if (bufferLineCount == maxScrollback) {
        Segment s = new Segment();

        int lastStart = 0;
        int newline = -1;
        while (true) {
          document.getText(lastStart, Math.min(1024, document.getLength()), s);
          for (int i = 0; i < s.count; i++) {
            if (s.array[s.offset + i] == '\n') {
              newline = i;
              break;
            }
          }
          if (newline != -1) {
            break;
          }

          lastStart += 1024;
          if (lastStart > document.getLength()) {
            // OOps.
            break;
          }
        }
        if (newline != -1) {
          document.remove(0, newline + 1);
        }
      } else {
        bufferLineCount++;
      }

      SimpleAttributeSet attrs = new SimpleAttributeSet();
      if (color != null) {
        attrs.addAttribute(StyleConstants.Foreground, color);
      }
      document.insertString(console.getText().length(), line, attrs);
    } catch (BadLocationException ble) {
      ble.printStackTrace();
    }
  }

  private class SbtHandler implements Visitor {

    private final String monitor;
    private final StringBuilder errorStream;
    private final StringBuilder output;

    private boolean waiting;
    private boolean monitoring;
    private boolean inWait;
    private Color currMatch;

    private final DefaultErrorSource errorSource;
    private boolean registered;
    private DefaultError error;

    SbtHandler(String monitor) {
      this.monitor = (monitor != null && !monitor.isEmpty()) ?
          monitor : null;
      this.errorStream = new StringBuilder();
      this.output = new StringBuilder();
      this.errorSource = new DefaultErrorSource(
          jEdit.getProperty("sbt.error_source_name"), view);
    }

    @Override
    public boolean process(byte[] buf, int len, boolean isError) {
      StringBuilder target = isError ? errorStream : output;

      if (buf != null) {
        for (int i = 0; i < len; i++) {
          target.append((char)buf[i]);
        }
      } else if (target.length() > 0) {
        target.append("\n");
      }

      int idx;
      while ((idx = target.indexOf("\n")) >= 0) {
        String line = target.substring(0, idx + 1);
        target.delete(0, idx + 1);
        process(line);
      }

      if (target.toString().equals("> ")) {
        if (waiting) {
          synchronized (this) {
            waiting = false;
            notifyAll();
          }
        } else if (monitor != null) {
          runCommand(monitor);
          monitoring = true;
        }

        append(target.toString(), null);
        target.setLength(0);
      }
      return true;
    }

    void runCommand(String command) {
      if (monitoring) {
        try {
          waiting = true;
          synchronized (this) {
            // Stop monitoring and wait for a prompt.
            stdin.write("\n".getBytes("UTF-8"));
            stdin.flush();
            while (waiting) {
              wait();
            }
            monitoring = false;
          }
        } catch (IOException ioe) {
          ioe.printStackTrace();
        } catch (InterruptedException ie) {
          ie.printStackTrace();
        }
      }

      command += "\n";
      try {
        stdin.write(command.getBytes("UTF-8"));
        stdin.flush();
      } catch (IOException ioe) {
        // Log.
        ioe.printStackTrace();
      }
    }

    private void process(String line) {
      if (isMatch(line, ERROR, ErrorSource.ERROR)) {
        currMatch = errorColor;
      } else if (isMatch(line, WARNING, ErrorSource.WARNING)) {
        currMatch = warningColor;
      } else if (currMatch != null) {
        Matcher m = EXTRA.matcher(line);
        if (m.matches()) {
          error.addExtraMessage(m.group(1));
        } else {
          currMatch = null;
        }
      }

      Color highlight = currMatch;
      if (currMatch == null) {
        if (isMatch(line, GENERIC_ERROR)) {
          highlight = errorColor;
        } else if (isMatch(line, GENERIC_WARNING)) {
          highlight = warningColor;
        }
      }

      if (inWait) {
        registered = false;
        errorSource.clear();
        inWait = false;
        ErrorSource.unregisterErrorSource(errorSource);
      } else {
        Matcher m = WAITING.matcher(line);
        if (m.matches()) {
          inWait = true;
        }
      }

      append(line, highlight);
    }

    private boolean isMatch(String line, Pattern regex) {
      return regex.matcher(line).matches();
    }

    private boolean isMatch(String line, Pattern regex, int type) {
      Matcher m = regex.matcher(line);
      if (m.matches()) {
        String path = m.group(1);
        int lineno = Integer.parseInt(m.group(2));
        error = new DefaultError(errorSource, type, path, lineno - 1,
            0, 0, m.group(3));
        errorSource.addError(error);

        if (!registered) {
          registered = true;
          ErrorSource.registerErrorSource(errorSource);
        }
        return true;
      }
      return false;
    }

    private void append(final String line, final Color color) {
      SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
          appendToConsole(line, color);
        }
      });
    }

  }

  private class CommandRunner implements ActionListener {

    @Override
    public void actionPerformed(ActionEvent ae) {
      String command = entry.getText().trim();
      if ("exit".equals(command)) {
        return;
      }
      handler.runCommand(command);
      entry.setText("");
    }

  }

  // TODO: remove from edit bus.

}

