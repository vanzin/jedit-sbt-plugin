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
import java.awt.Component;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.List;
import java.util.LinkedList;
import java.util.Properties;

import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.DefaultListModel;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;

import org.gjt.sp.jedit.AbstractOptionPane;
import org.gjt.sp.jedit.jEdit;

import projectviewer.vpt.VPTProject;

public class SbtOptionsPane extends AbstractOptionPane {

  private static final String LABEL_PREFIX = "options.sbt.config.";

  public static final String SBT_ENABLED = "sbt.enabled";
  private static final String SBT_MONITOR_CMD = "sbt.monitor_cmd";
  private static final String SBT_ENV_VAR_NAME = "sbt.env.%d.name";
  private static final String SBT_ENV_VAR_VALUE = "sbt.env.%d.value";

  private final VPTProject project;

  private JCheckBox enabled;
  private JTextField monitor;

  private JTextField envName;
  private JTextField envValue;
  private JList env;
  private DefaultListModel envModel;

  public SbtOptionsPane(VPTProject project) {
    super("sbt.config");
    this.project = project;
  }

  @Override
  public void _init() {
    this.enabled = new JCheckBox();
    this.enabled.setSelected(getBoolean(SBT_ENABLED));
    addComponent(getLabel(SBT_ENABLED), this.enabled);

    Properties props = project.getProperties();

    this.monitor = new JTextField();
    this.monitor.setText(getMonitorCmd(project));
    this.monitor.setToolTipText(getLabel(SBT_MONITOR_CMD + ".tooltip"));
    addComponent(getLabel(SBT_MONITOR_CMD), this.monitor);

    addSeparator(getLabel("env.separator"));

    GridBagLayout gbl = new GridBagLayout();
    GridBagConstraints gbc = new GridBagConstraints();
    JPanel envEdit = new JPanel(gbl);
    JLabel label;

    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0.0;
    gbc.gridx = 0;
    label = new JLabel(getLabel("env.name"));
    gbl.setConstraints(label, gbc);
    envEdit.add(label);

    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 2.0;
    gbc.gridx++;
    this.envName = new JTextField();
    gbl.setConstraints(envName, gbc);
    envEdit.add(envName);

    gbc.fill = GridBagConstraints.NONE;
    gbc.weightx = 0.0;
    gbc.gridx++;
    label = new JLabel(getLabel("env.value"));
    gbl.setConstraints(label, gbc);
    envEdit.add(label);

    gbc.fill = GridBagConstraints.HORIZONTAL;
    gbc.weightx = 2.0;
    gbc.gridx++;
    this.envValue = new JTextField();
    gbl.setConstraints(envValue, gbc);
    envEdit.add(envValue);

    gbc.fill = GridBagConstraints.NONE;
    gbc.gridx++;
    gbc.weightx = 0.0;
    JButton add = new JButton("+");
    gbl.setConstraints(add, gbc);
    envEdit.add(add);
    add.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent ae) {
        if (envName.getText().isEmpty()) {
          JOptionPane.showMessageDialog(SbtOptionsPane.this,
              getLabel("error.no_env_name"));
          return;
        }

        boolean found = false;
        for (int i = 0; i < envModel.getSize(); i++) {
          EnvVar var = (EnvVar) envModel.elementAt(i);
          if (var.name.equals(envName.getText())) {
            var.value = envValue.getText();
            found = true;
            envModel.setElementAt(var, i);
            break;
          }
        }

        if (!found) {
          EnvVar var = new EnvVar(envName.getText(), envValue.getText());
          envModel.addElement(var);
        }

        revalidate();
      }
    });

    JButton remove = new JButton("-");
    gbc.gridx++;
    gbc.gridwidth = GridBagConstraints.REMAINDER;
    gbl.setConstraints(remove, gbc);
    envEdit.add(remove);
    remove.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent ae) {
        int selected = env.getSelectedIndex();
        if (selected < 0) {
          return;
        }

        envModel.remove(selected);
        revalidate();
      }
    });

    addComponent(envEdit, GridBagConstraints.HORIZONTAL);

    this.envModel = new DefaultListModel();
    for (EnvVar v : getEnv(project)) {
      this.envModel.addElement(v);
    }

    this.env = new JList(this.envModel);
    this.env.setCellRenderer(new EnvVarRenderer());
    this.env.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
    addComponent(env, GridBagConstraints.BOTH);
  }

  @Override
  public void _save() {
    Properties props = project.getProperties();

    props.setProperty(SBT_ENABLED, String.valueOf(enabled.isSelected()));
    props.setProperty(SBT_MONITOR_CMD, monitor.getText());

    int i;
    for (i = 0; i < envModel.getSize(); i++) {
      EnvVar var = (EnvVar) envModel.elementAt(i);
      props.setProperty(String.format(SBT_ENV_VAR_NAME, i), var.name);
      props.setProperty(String.format(SBT_ENV_VAR_VALUE, i), var.value);
    }

    while (true) {
      String name = props.getProperty(String.format(SBT_ENV_VAR_NAME, i));
      String value = props.getProperty(String.format(SBT_ENV_VAR_VALUE, i));
      if (name != null || value != null) {
        props.remove(String.format(SBT_ENV_VAR_NAME, i));
        props.remove(String.format(SBT_ENV_VAR_VALUE, i));
      } else {
        break;
      }
      i++;
    }

  }

  private boolean getBoolean(String name) {
    return getBoolean(project, name);
  }

  public static boolean getBoolean(VPTProject p, String name) {
    return "true".equalsIgnoreCase(p.getProperties().getProperty(name));
  }

  public static List<EnvVar> getEnv(VPTProject project) {
    Properties props = project.getProperties();
    List<EnvVar> env = new LinkedList<>();
    int idx = 0;
    while (true) {
      String name = props.getProperty(String.format(SBT_ENV_VAR_NAME, idx));
      String value = props.getProperty(String.format(SBT_ENV_VAR_VALUE, idx));
      if (name == null || value == null) {
        break;
      }
      env.add(new EnvVar(name, value));
      idx++;
    }
    return env;
  }

  public static String getMonitorCmd(VPTProject project) {
    return project.getProperties().getProperty(SBT_MONITOR_CMD);
  }

  private String getLabel(String opt) {
    return jEdit.getProperty(LABEL_PREFIX + opt);
  }

  public static class EnvVar {
    final String name;
    String value;

    EnvVar(String name, String value) {
      this.name = name;
      this.value = value;
    }
  }

  private class EnvVarRenderer extends JLabel
      implements ListCellRenderer {

    @Override
    public Component getListCellRendererComponent(JList list,
        Object value,
        int index,
        boolean isSelected,
        boolean cellHasFocus) {
      EnvVar var = (EnvVar) value;
      setText(String.format("%s: %s", var.name, var.value));
      return this;
    }

  }

}

