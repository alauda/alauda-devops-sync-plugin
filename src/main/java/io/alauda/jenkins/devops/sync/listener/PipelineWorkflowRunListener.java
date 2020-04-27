package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineWorkflowRunListener extends RunListener<WorkflowRun> {

  private static final Logger logger = LoggerFactory.getLogger(PipelineWorkflowRunListener.class);

  @Override
  public void onStarted(WorkflowRun run, TaskListener listener) {
    if (!shouldPollRun(run)) {
      return;
    }
    PipelineSyncExecutor.getInstance().submit(run);

    updateParams(run);
    logger.debug("onStarted {}", run.getFullDisplayName());
  }

  @Override
  public void onCompleted(WorkflowRun run, @Nonnull TaskListener listener) {
    if (!shouldPollRun(run)) {
      return;
    }
    PipelineSyncExecutor.getInstance().submit(run);

    updateParams(run);
    logger.debug("onCompleted {}", run.getFullDisplayName());
  }

  @Override
  public void onDeleted(WorkflowRun run) {
    if (!shouldPollRun(run)) {
      return;
    }

    JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
    if (cause != null) {
      String namespace = cause.getNamespace();
      String name = cause.getName();

      PipelineUtils.delete(namespace, name);
      logger.debug("Deleted Pipeline `{}/{}`", namespace, name);
    }

    logger.debug("onDeleted {}", run.getFullDisplayName());
  }

  @Override
  public void onFinalized(WorkflowRun run) {
    if (!shouldPollRun(run)) {
      return;
    }
    PipelineSyncExecutor.getInstance().submit(run);

    logger.debug("onFinalized {}", run.getFullDisplayName());
  }

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   * @return true if the should poll the status of this build run
   */
  private boolean shouldPollRun(WorkflowRun run) {
    return getAlaudaJobProperty(run) != null;
  }

  private AlaudaJobProperty getAlaudaJobProperty(WorkflowRun run) {
    if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
      return null;
    }

    WorkflowJob job = run.getParent();
    ItemGroup parent = job.getParent();

    if (parent instanceof WorkflowMultiBranchProject) {
      WorkflowMultiBranchProject multiBranchProject = ((WorkflowMultiBranchProject) parent);
      return multiBranchProject.getProperties().get(MultiBranchProperty.class);
    }

    return WorkflowJobUtils.getAlaudaProperty(job);
  }

  private void updateParams(WorkflowRun run) {
    AlaudaJobProperty property = getAlaudaJobProperty(run);
    if (property == null) {
      return;
    }

    WorkflowJob job = run.getParent();
    if (job.getParent() instanceof WorkflowMultiBranchProject
        && WorkflowJobUtils.parametersHasChange(job)) {
      WorkflowJobUtils.updateBranchAndPRAnnotations(job);
    } else {
      String namespace = property.getNamespace();
      String name = property.getName();
      V1alpha1PipelineConfig pc =
          Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
      if (pc == null) {
        logger.info(
            "can not found pipelineconfig by namespace: "
                + namespace
                + ", name: "
                + name
                + "; skip update parameters");
        return;
      }

      V1alpha1PipelineConfig newPC = DeepCopyUtils.deepCopy(pc);
      PipelineConfigToJobMapper.updateParameters(job, newPC);
      Clients.get(V1alpha1PipelineConfig.class).update(pc, newPC);

      logger.info("update parameter done, namespace: " + namespace + ", name: " + name);
    }
  }
}
