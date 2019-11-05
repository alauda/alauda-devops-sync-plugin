package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.event.PipelineConfigEvents;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
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
    updatePipelineConfigWithBranchAnnotation(item, "add");
  }

  @Override
  public void onUpdated(WorkflowJob item) {
    updatePipelineConfigWithBranchAnnotation(item, "update");
  }

  @Override
  public void onDeleted(WorkflowJob item) {
    updatePipelineConfigWithBranchAnnotation(item, "delete");
  }

  private void updatePipelineConfigWithBranchAnnotation(WorkflowJob item, String action) {
    WorkflowJobUtils.updateAnnotations(item);

    BranchJobProperty branchJobProperty = item.getProperty(BranchJobProperty.class);
    V1alpha1PipelineConfig pipelineConfig = getPipelineConfigFromBranchJob(item);
    if (branchJobProperty != null && pipelineConfig != null) {
      PipelineConfigEvents.newJobUpdatedEvent(
              pipelineConfig,
              String.format(
                  "Branches changed, %s branch %s, branch deleted: %s",
                  action, branchJobProperty.getBranch(), item.isDisabled()))
          .submit();
    }
  }

  private V1alpha1PipelineConfig getPipelineConfigFromBranchJob(WorkflowJob branchJob) {
    JenkinsClient client = JenkinsClient.getInstance();

    WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) branchJob.getParent();
    MultiBranchProperty property = client.getMultiBranchProperty(parent);
    if (property != null) {
      return client.getPipelineConfigFromPipeline(
          new NamespaceName(property.getNamespace(), property.getName()));
    }
    return null;
  }
}
