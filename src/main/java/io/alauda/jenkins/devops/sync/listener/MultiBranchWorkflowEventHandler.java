package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

@Extension
@Restricted(DoNotUse.class)
public class MultiBranchWorkflowEventHandler implements ItemEventHandler<WorkflowJob> {
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
