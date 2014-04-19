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

