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

import org.gjt.sp.jedit.OptionGroup;
import org.gjt.sp.jedit.OptionPane;

import projectviewer.config.OptionsService;
import projectviewer.vpt.VPTProject;

public class SbtOptionsService implements OptionsService {

  @Override
  public OptionPane getOptionPane(VPTProject proj) {
    return new SbtOptionsPane(proj);
  }

  @Override
  public OptionGroup getOptionGroup(VPTProject proj) {
    return null;
  }

}

