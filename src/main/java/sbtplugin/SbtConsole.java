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
import java.awt.ComponentOrientation;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.JToggleButton;
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
import org.gjt.sp.jedit.msg.ViewUpdate;
import org.gjt.sp.util.IOUtilities;
import org.gjt.sp.util.Log;

import common.io.ProcessExecutor;
import common.io.ProcessExecutor.Visitor;
import errorlist.DefaultErrorSource;
import errorlist.DefaultErrorSource.DefaultError;
import errorlist.ErrorSource;
import projectviewer.ProjectViewer;
import projectviewer.event.ProjectUpdate;
import projectviewer.event.ViewerUpdate;
import projectviewer.vpt.VPTProject;

public class SbtConsole extends JPanel {

  private static final String HISTORY_KEY =
      SbtConsole.class.getName() + ".commands";
  private static final String SBT_DISABLED = "sbt.disabled.msg";

  private static final Pattern ERROR =
      Pattern.compile("\\s*\\[error\\]\\s+(.+?):([0-9]+):(?:[0-9]+:)?\\s*(.*)[\r\n]+");
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

  private static final ConsoleUpdate POISON_PILL = new ConsoleUpdate(null, null);

  private final JTextPane console;
  private final DefaultStyledDocument document;
  private final View view;

  private final JPanel entryPanel;
  private final HistoryTextField entry;
  private final JButton clearButton;
  private final JButton reloadButton;

  private final List<ErrorMatcher> matchers;

  private final BlockingQueue<ConsoleUpdate> updates;

  private Color plainColor;
  private Color infoColor;
  private Color warningColor;
  private Color errorColor;

  private String sbtCommand;
  private String sbtCmdLineArgs;
  private Map<String, String> sbtEnv;
  private SbtHandler handler;
  private Process sbtProcess;
  private ProcessExecutor sbt;
  private OutputStream stdin;
  private int bufferLineCount;
  private int maxScrollback;
  private ExecutorService streams;
  private ExecutorService drainer;
  private File wrapper;
  private VPTProject project;

  public SbtConsole(View view) {
    super(new BorderLayout());
    this.view = view;

    entryPanel = new JPanel(new BorderLayout());
    entryPanel.setEnabled(false);
    entryPanel.add(BorderLayout.WEST,
        new JLabel(jEdit.getProperty("sbtplugin.shell.entry")));

    entry = new HistoryTextField(HISTORY_KEY);
    entry.setEnterAddsToHistory();
    entry.addActionListener(new CommandRunner());

    entryPanel.add(BorderLayout.CENTER, entry);

    JPanel buttons = new JPanel(new FlowLayout());
    buttons.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);

    clearButton = new JButton();
    clearButton.setIcon(new ImageIcon(
      getClass().getResource("/clear.png")));
    clearButton.setPreferredSize(new Dimension(24, 24));
    clearButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent ae) {
        if (sbt != null) {
          clearBuffer();
          if (handler.prompt != null) {
            appendToConsole(handler.prompt, plainColor);
          }
        }
      }
    });
    buttons.add(clearButton);

    reloadButton = new JButton();
    reloadButton.setIcon(new ImageIcon(
      getClass().getResource("/reload.png")));
    reloadButton.setPreferredSize(new Dimension(24, 24));
    reloadButton.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent ae) {
        if (sbt != null) {
          VPTProject p = project;
          stopSbt();
          startSbt(p);
        }
      }
    });
    buttons.add(reloadButton);

    entryPanel.add(BorderLayout.EAST, buttons);
    add(BorderLayout.NORTH, entryPanel);

    document = new DefaultStyledDocument();
    console = new JTextPane(document);
    console.setEditable(false);
    add(BorderLayout.CENTER, new JScrollPane(console));
    propertiesChanged(null);
    EditBus.addToBus(this);

    this.matchers = new ArrayList<>();
    matchers.add(new ErrorMatcher(ERROR, EXTRA, ErrorSource.ERROR));
    matchers.add(new ErrorMatcher(WARNING, EXTRA, ErrorSource.WARNING));
    matchers.add(new ErrorMatcher(GENERIC_ERROR, null, ErrorSource.ERROR));
    matchers.add(new ErrorMatcher(GENERIC_WARNING, null, ErrorSource.WARNING));

    this.updates = new ArrayBlockingQueue<>(1024);

    startSbt(ProjectViewer.getActiveProject(view));
  }

  public void focusEntryField() {
    entry.requestFocus();
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

  @EBHandler
  public void projectUpdate(ProjectUpdate update) {
    if (update.getType() != ProjectUpdate.Type.PROPERTIES_CHANGED) {
      return;
    }

    VPTProject project = update.getProject();
    Map<String, String> env = new HashMap<>();
    for (SbtOptionsPane.EnvVar v : SbtOptionsPane.getEnv(project)) {
      env.put(v.name, v.value);
    }

    boolean stop = false;

    if (!SbtOptionsPane.getSbtCommand(project).equals(sbtCommand)) {
      stop = true;
    }

    if (!SbtOptionsPane.get(project, SbtOptionsPane.SBT_CMD_LINE_ARGS)
      .equals(sbtCmdLineArgs)) {
      stop = true;
    }

    if (!env.equals(sbtEnv) || !isEnabled(project)) {
      stop = true;
    }

    if (stop) {
      stopSbt();
    }

    if (stop || (sbt == null && isEnabled(project))) {
      startSbt(project);
    }

    // Will set the message if sbt is disabled.
    if (sbt == null) {
      return;
    }
  }

  @EBHandler
  public void viewUpdate(ViewUpdate vu) {
    if (vu.getView() == view && vu.getWhat() == ViewUpdate.CLOSED) {
      EditBus.removeFromBus(this);
      stopSbt();
    }
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

    InputStream in = null;
    OutputStream out = null;
    try {
      wrapper = File.createTempFile("sbt-wrapper.", ".py");
      in = getClass().getResourceAsStream("/sbt_wrapper.py");
      out = new FileOutputStream(wrapper);
      IOUtilities.copyStream(null, in, out, false);
    } catch (IOException ioe) {
      JOptionPane.showMessageDialog(view,
          jEdit.getProperty("sbt.error.child",
                            new Object[] { ioe.getMessage() }));
      ioe.printStackTrace();
      return;
    } finally {
      IOUtilities.closeQuietly(in);
      IOUtilities.closeQuietly(out);
    }
    wrapper.setExecutable(true);

    ThreadFactory factory = new ThreadFactory() {
      private final AtomicInteger id = new AtomicInteger();

      @Override
      public Thread newThread(Runnable r) {
        Thread t = new Thread(r);
        t.setName(String.format("SBT-%s-%d", project.getName(),
            id.incrementAndGet()));
        return t;
      }
    };

    this.streams = Executors.newFixedThreadPool(3, factory);
    this.drainer = Executors.newSingleThreadScheduledExecutor(factory);
    drainer.submit(new ConsoleUpdater());

    String sbtCommand = SbtOptionsPane.getSbtCommand(project);
    String cmdLineArgs = SbtOptionsPane.get(project,
      SbtOptionsPane.SBT_CMD_LINE_ARGS);
    String[] args;
    if (cmdLineArgs != null && !cmdLineArgs.isEmpty()) {
      args = new String[5];
      args[4] = cmdLineArgs;
    } else {
      args = new String[4];
    }
    args[0] = "python";
    args[1] = "-u";
    args[2] = wrapper.getAbsolutePath();
    args[3] = sbtCommand;

    ProcessExecutor pe = new ProcessExecutor(args);
    pe.setExecutor(streams);
    pe.addCurrentEnv();

    Map<String, String> env = new HashMap<>();
    for (SbtOptionsPane.EnvVar v : SbtOptionsPane.getEnv(project)) {
      pe.addEnv(v.name, v.value);
      env.put(v.name, v.value);
    }
    pe.setDirectory(project.getRootPath());

    this.handler = new SbtHandler();
    pe.addVisitor(handler);

    try {
      this.sbtProcess = pe.start();
    } catch (IOException ioe) {
      JOptionPane.showMessageDialog(view,
          jEdit.getProperty("sbt.error.child",
                            new Object[] { ioe.getMessage() }));
      streams.shutdownNow();
      streams = null;
      return;
    }
    this.stdin = sbtProcess.getOutputStream();
    this.sbt = pe;
    this.sbtEnv = env;
    this.sbtCommand = sbtCommand;
    this.sbtCmdLineArgs = cmdLineArgs;
    this.project = project;
    entryPanel.setEnabled(true);
  }

  private void stopSbt() {
    if (sbt != null) {
      try {
        handler.stopNow();
      } catch (InterruptedException ie) {
        // Nothing to do.
      }
      streams.shutdown();
      wrapper.delete();
      drainer.shutdown();
      try {
        drainer.awaitTermination(1, TimeUnit.SECONDS);
      } catch (InterruptedException ie) {
        // Ignore.
      }
    }

    clearBuffer();

    if (sbt == null) {
      return;
    }

    handler.unregisterSource();

    this.sbt = null;
    this.handler = null;
    this.streams = null;
    this.wrapper = null;
    this.sbtCommand = null;
    this.sbtCmdLineArgs = null;
    this.sbtEnv = null;
    this.project = null;
    entryPanel.setEnabled(false);
  }

  private boolean isEnabled(VPTProject p) {
    return SbtOptionsPane.getBoolean(p, SbtOptionsPane.SBT_ENABLED);
  }

  private void appendToConsole(String line, Color color) {
    appendToConsole(Arrays.asList(new ConsoleUpdate(line, color)));
  }

  private void appendToConsole(List<ConsoleUpdate> batch) {
    try {
      if (bufferLineCount + batch.size() > maxScrollback) {
        int linesToDelete = bufferLineCount + batch.size() -
          maxScrollback;
        Segment s = new Segment();

        int lastStart = 0;
        int lines = 0;
        int cropIndex = -1;
        while (true) {
          int len = Math.min(1024, document.getLength());
          document.getText(lastStart, len, s);
          for (int i = 0; i < s.count; i++) {
            if (s.array[s.offset + i] == '\n') {
              lines++;
              cropIndex = i;
              break;
            }
          }

          if (lines == linesToDelete) {
            break;
          }

          lastStart += len;
          if (lastStart > document.getLength()) {
            break;
          }
        }
        if (cropIndex != -1) {
          document.remove(0, cropIndex + 1);
        }
      } else {
        bufferLineCount += batch.size();
      }

      for (ConsoleUpdate update : batch) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        if (update.color != null) {
          attrs.addAttribute(StyleConstants.Foreground, update.color);
        }
        document.insertString(console.getText().length(),
          update.content, attrs);
      }
    } catch (BadLocationException ble) {
      ble.printStackTrace();
    }
  }

  private void clearBuffer() {
    try {
      document.remove(0, document.getLength());
    } catch (BadLocationException ble) {
      Log.log(Log.ERROR, this, ble);
    }
    bufferLineCount = 0;
  }

  private class SbtHandler implements Visitor {

    private final StringBuilder errorStream;
    private final StringBuilder output;
    private final Queue<String> commandQueue;

    private boolean monitoring;
    private boolean inWait;
    private String prompt;
    private ErrorMatcher currMatcher;

    private final DefaultErrorSource errorSource;
    private boolean registered;
    private DefaultError error;

    SbtHandler() {
      this.errorStream = new StringBuilder();
      this.output = new StringBuilder();
      this.errorSource = new DefaultErrorSource(
          jEdit.getProperty("sbt.error_source_name"), view);
      this.commandQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    public boolean process(byte[] buf, int len, boolean isError) {
      StringBuilder target = isError ? errorStream : output;
      prompt = null;

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

      String currTarget = target.toString();
      if (currTarget.equals("> ") || currTarget.endsWith("? ")) {
        append(currTarget, null);
        if (!commandQueue.isEmpty()) {
          nextCommand();
        } else {
          prompt = currTarget;
        }
        target.setLength(0);
      }
      return true;
    }

    void runCommand(String command) {
      if (monitoring) {
        try {
          stdin.write("\n".getBytes("UTF-8"));
          stdin.flush();
          monitoring = false;
        } catch (IOException ioe) {
          ioe.printStackTrace();
        }
        if (!command.isEmpty()) {
          commandQueue.offer(command);
        }
      } else {
        commandQueue.offer(command);
        if (prompt != null) {
          nextCommand();
        }
      }
    }

    void stopNow() throws InterruptedException {
      if (inWait || "> ".equals(prompt)) {
        runCommand("exit");
      } else {
        try {
          stdin.close();
        } catch (IOException ioe) {
          ioe.printStackTrace();
        }
        sbtProcess.destroy();
      }
      sbt.waitFor();
    }

    private synchronized void nextCommand() {
      String command = commandQueue.poll();
      if (command == null) {
        return;
      }

      unregisterSource();
      try {
        stdin.write((command  + "\n").getBytes("UTF-8"));
        stdin.flush();
      } catch (IOException ioe) {
        // Log.
        ioe.printStackTrace();
      }

      if (command.startsWith("~")) {
        monitoring = true;
      }
    }

    private void process(String line) {
      // First see if any error matcher's first line regexp matches.
      // Error matchers are those that can match multiple lines (and
      // thus have an "extra" regexp). Other matchers are just for
      // highlighting and do not stop the current matcher.
      ErrorMatcher match = null;
      for (ErrorMatcher em : matchers) {
        if (em.extra != null && isMatch(line, em.first, em.type)) {
          match = em;
          break;
        }
      }

      if (match != null) {
        currMatcher = match;
      } else if (currMatcher != null) {
        Matcher m = currMatcher.extra.matcher(line);
        if (m.matches()) {
          error.addExtraMessage(m.group(1));
        } else {
          currMatcher = null;
        }
      }

      // If there isn't a current match, try the generic matchers (those
      // with no "extra" line matcher).
      if (currMatcher == null) {
        for (ErrorMatcher em : matchers) {
          if (em.extra == null && isMatch(line, em.first, em.type)) {
            currMatcher = em;
            break;
          }
        }
      }

      Color highlight = null;
      if (currMatcher != null) {
        switch (currMatcher.type) {
        case ErrorSource.ERROR:
          highlight = errorColor;
          break;
        case ErrorSource.WARNING:
          highlight = warningColor;
          break;
        }
      }

      if (currMatcher != null && currMatcher.extra == null) {
        currMatcher = null;
      }

      if (inWait) {
        unregisterSource();
        inWait = false;
      } else {
        Matcher m = WAITING.matcher(line);
        if (m.matches()) {
          inWait = true;
        }
      }

      append(line, highlight);
    }

    private void unregisterSource() {
      if (registered) {
        registered = false;
        errorSource.clear();
        ErrorSource.unregisterErrorSource(errorSource);
      }
    }

    private boolean isMatch(String line, Pattern regex, int type) {
      Matcher m = regex.matcher(line);
      if (m.matches()) {
        if (m.groupCount() > 0) {
          String path = m.group(1);
          int lineno = Integer.parseInt(m.group(2));
          error = new DefaultError(errorSource, type, path, lineno - 1,
              0, 0, m.group(3));
          errorSource.addError(error);

          if (!registered) {
            registered = true;
            ErrorSource.registerErrorSource(errorSource);
          }
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

  private class ConsoleUpdater implements Runnable {

    @Override
    public void run() {
      try {
        while (sbt != null) {
          final List<ConsoleUpdate> batch = new ArrayList<>();
          ConsoleUpdate next = updates.take();
          if (next == POISON_PILL) {
            break;
          }

          batch.add(next);

          // Give some time to coalesce updates.
          TimeUnit.MILLISECONDS.sleep(50);

          updates.drainTo(batch);
          SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
              appendToConsole(batch);
            }
          });
        }
      } catch (InterruptedException ie) {
        // Nothing to do.
      }
    }

  }

  private static class ErrorMatcher {

    final Pattern first;
    final Pattern extra;
    final int type;

    ErrorMatcher(Pattern first, Pattern extra, int type) {
      this.first = first;
      this.extra = extra;
      this.type = type;
    }

  }

  private static class ConsoleUpdate {

      final String content;
      final Color color;

      ConsoleUpdate(String content, Color color) {
          this.content = content;
          this.color = color;
      }

  }

  // TODO: remove from edit bus.

}
