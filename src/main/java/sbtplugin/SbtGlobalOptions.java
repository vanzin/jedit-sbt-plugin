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

import javax.swing.JOptionPane;
import javax.swing.JTextField;

import org.gjt.sp.jedit.AbstractOptionPane;
import org.gjt.sp.jedit.jEdit;

import common.gui.FileTextField;

public class SbtGlobalOptions extends AbstractOptionPane {

  static final String SBT_COMMAND = "sbt.command";
  private static final String SBT_MAX_SCROLLBACK = "sbt.max_scrollback";
  private static final int SBT_MAX_SCROLLBACK_DEFAULT = 1024;

  public static final String getSbtCommand() {
    return jEdit.getProperty(SBT_COMMAND, "sbt");
  }

  public static final int getMaxScrollback() {
    return jEdit.getIntegerProperty(SBT_MAX_SCROLLBACK,
        SBT_MAX_SCROLLBACK_DEFAULT);
  }

  private FileTextField sbtCommand;
  private String oldPath;
  private JTextField maxScrollback;

  public SbtGlobalOptions() {
    super("sbt.global");
  }

  @Override
  protected void _init() {
    oldPath = getSbtCommand();
    sbtCommand = new FileTextField(oldPath, false);
    addComponent(jEdit.getProperty("options.sbt.command"), sbtCommand);

    maxScrollback = new JTextField();
    maxScrollback.setText(String.valueOf(getMaxScrollback()));
    addComponent(jEdit.getProperty("options.sbt.max_scrollback"), maxScrollback);
  }

  @Override
  protected void _save() {
    String newPath = sbtCommand.getTextField().getText();
    if (newPath != oldPath) {
      jEdit.setProperty(SBT_COMMAND, newPath);
    }

    try {
      int newMaxScrollback = Integer.parseInt(maxScrollback.getText());
      if (newMaxScrollback <= 0) {
        throw new NumberFormatException();
      }
      if (newMaxScrollback != SBT_MAX_SCROLLBACK_DEFAULT) {
        jEdit.setIntegerProperty(SBT_MAX_SCROLLBACK, newMaxScrollback);
      }
    } catch (NumberFormatException nfe) {
      JOptionPane.showMessageDialog(this,
          jEdit.getProperty("options.sbt.max_scrollback.error"));
    }
  }

}

