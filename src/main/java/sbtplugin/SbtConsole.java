package sbtplugin;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.IOException;
import java.io.OutputStream;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.EditBus;
import org.gjt.sp.jedit.EditBus.EBHandler;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.gui.HistoryTextField;
import org.gjt.sp.jedit.msg.PropertiesChanged;
import org.gjt.sp.util.Log;

import common.io.ProcessExecutor;
import common.io.ProcessExecutor.Visitor;
import projectviewer.ProjectViewer;
import projectviewer.event.ViewerUpdate;
import projectviewer.vpt.VPTProject;

public class SbtConsole extends JPanel {

  private static final String HISTORY_KEY =
      SbtShell.class.getName() + ".commands";

  private final HistoryTextField entry;
  private final JTextArea console;
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

    console = new JTextArea();
    console.setEditable(false);
    add(BorderLayout.CENTER, new JScrollPane(console));
    propertiesChanged(null);
    EditBus.addToBus(this);

    VPTProject active = ProjectViewer.getActiveProject(view);
    if (active != null && isEnabled(active)) {
      startSbt(active);
    }
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

    boolean enabled = false;
    if (update.getType() == ViewerUpdate.Type.PROJECT_LOADED) {
      enabled = isEnabled((VPTProject) update.getNode());
    }

    if (enabled) {
      if (sbt == null) {
        startSbt((VPTProject) update.getNode());
      }
    } else {
      if (sbt != null) {
        stopSbt();
      }
    }
  }

  private void startSbt(VPTProject project) {
    ProcessExecutor pe = new ProcessExecutor(
        SbtGlobalOptions.getSbtCommand(),
        "-Dsbt.log.noformat=true");
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
      return;
    }
    this.sbt = pe;
  }

  private void stopSbt() {
    handler.runCommand("exit");
    try {
      sbt.waitFor();
    } catch (InterruptedException ie) {
      // Nothing to do.
    }
    SwingUtilities.invokeLater(new Runnable() {
      @Override
      public void run() {
        console.setText("");
        bufferLineCount = 0;
      }
    });

    this.sbt = null;
    this.handler = null;
  }

  private boolean isEnabled(VPTProject p) {
    return SbtOptionsPane.getBoolean(p, SbtOptionsPane.SBT_ENABLED);
  }

  private void appendToConsole(String line) {
    if (bufferLineCount == maxScrollback) {
      String curr = console.getText();
      for (int i = 0; i < curr.length(); i++) {
        if (curr.charAt(i) == '\n') {
          console.setText(curr.substring(i + 1));
          break;
        }
      }
    } else {
      bufferLineCount++;
    }
    console.append(line);
  }

  private class SbtHandler implements Visitor {

    private final String monitor;
		private final StringBuilder error;
		private final StringBuilder output;

		private boolean waiting;
    private boolean monitoring;

		SbtHandler(String monitor) {
		  this.monitor = (monitor != null && !monitor.isEmpty()) ?
		      monitor : null;
		  this.error = new StringBuilder();
		  this.output = new StringBuilder();
		}

    @Override
		public boolean process(byte[] buf, int len, boolean isError) {
			StringBuilder target = isError ? error : output;

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

			  append(target.toString());
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
		  append(line);
		}

		private void append(final String line) {
      SwingUtilities.invokeLater(new Runnable() {
        @Override
        public void run() {
          appendToConsole(line);
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

  // TODO: start child process, monitor / parse output.
  // TODO: handle user commands.
  // TODO: remove from edit bus.

}

