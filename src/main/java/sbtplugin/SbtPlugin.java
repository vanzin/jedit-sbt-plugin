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

import org.gjt.sp.jedit.EditBus;
import org.gjt.sp.jedit.EditBus.EBHandler;
import org.gjt.sp.jedit.EditPlugin;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.msg.ViewUpdate;
import projectviewer.ProjectViewer;
import projectviewer.event.ViewerUpdate;
import projectviewer.vpt.VPTProject;

public class SbtPlugin extends EditPlugin {

  @Override
  public void start() {
    EditBus.addToBus(this);
    for (View v : jEdit.getViews()) {
      checkDockable(v);
    }
  }

  @Override
  public void stop() {
    EditBus.removeFromBus(this);
  }

  @EBHandler
  public void projectViewerUpdate(ViewerUpdate update) {
    if (update.getType() == ViewerUpdate.Type.PROJECT_LOADED) {
      checkDockable(update.getView());
    }
  }

  @EBHandler
  public void viewUpdate(ViewUpdate update) {
    if (update.getWhat() == ViewUpdate.CREATED) {
      checkDockable(update.getView());
    }
  }

  private void checkDockable(View view) {
    VPTProject project = ProjectViewer.getActiveProject(view);
    if (project == null) {
      return;
    }

    Object console = view.getDockableWindowManager()
        .getDockable("sbtconsole");
    if (console != null) {
      return;
    }

    if (SbtOptionsPane.getBoolean(project, SbtOptionsPane.SBT_ENABLED)) {
      jEdit.getAction("sbtconsole").invoke(view);
    }
  }

}
