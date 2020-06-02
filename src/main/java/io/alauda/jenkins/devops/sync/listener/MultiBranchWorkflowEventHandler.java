package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
@Restricted(DoNotUse.class)
public class MultiBranchWorkflowEventHandler implements ItemEventHandler<WorkflowJob> {

  private static final Logger logger =
      LoggerFactory.getLogger(MultiBranchWorkflowEventHandler.class);

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
    addToSyncExecutor(item);
  }

  @Override
  public void onUpdated(WorkflowJob item) {
    addToSyncExecutor(item);
  }

  @Override
  public void onDeleted(WorkflowJob item) {
    addToSyncExecutor(item);
  }

  private void addToSyncExecutor(WorkflowJob item) {
    WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();
    AlaudaJobProperty pro = parent.getProperties().get(MultiBranchProperty.class);
    if (pro == null) {
      logger.debug("MultibranchProject {} is not created by PipelineConfig, will skip it", parent);
      return;
    }

    String namespace = pro.getNamespace();
    String name = pro.getName();

    MultibranchProjectSyncExecutor.getInstance().submit(new NamespaceName(namespace, name));
  }
}
