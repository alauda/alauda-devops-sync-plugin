package io.alauda.jenkins.devops.sync.listener.pipeline;

import hudson.Extension;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineWorkflowRunListener extends RunListener<WorkflowRun> {

  private static final Logger logger = LoggerFactory.getLogger(PipelineWorkflowRunListener.class);

  @Override
  public void onStarted(WorkflowRun run, TaskListener listener) {
    super.onStarted(run, listener);
  }

  @Override
  public void onCompleted(WorkflowRun run, @Nonnull TaskListener listener) {
    super.onCompleted(run, listener);
  }

  @Override
  public void onDeleted(WorkflowRun run) {
    super.onDeleted(run);
  }

  @Override
  public void onFinalized(WorkflowRun run) {
    if (PipelineUtils.findAlaudaCause(run) == null) {
      return;
    }

    PipelineSyncExecutor.getInstance().submit(run);
  }
}
