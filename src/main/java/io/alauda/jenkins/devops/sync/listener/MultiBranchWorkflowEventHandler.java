package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

@Extension
@Restricted(DoNotUse.class)
public class MultiBranchWorkflowEventHandler implements ItemEventHandler<WorkflowJob> {
  private static final Logger logger =
      Logger.getLogger(MultiBranchWorkflowEventHandler.class.getName());

  @Override
  public boolean accept(Item item) {
    if (item == null) {
      return false;
    }

    ItemGroup<? extends Item> parent = item.getParent();
    return (parent instanceof WorkflowMultiBranchProject);
  }

  @Override
  public void onCreated(WorkflowJob item) {
    WorkflowJobUtils.updateBranchAndPRAnnotations(item);
  }

  @Override
  public void onUpdated(WorkflowJob item) {
    WorkflowJobUtils.updateBranchAndPRAnnotations(item);
  }

  @Override
  public void onDeleted(WorkflowJob item) {
    WorkflowJobUtils.updateBranchAndPRAnnotations(item, true);
  }
}
