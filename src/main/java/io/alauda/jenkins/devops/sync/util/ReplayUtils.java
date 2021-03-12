package io.alauda.jenkins.devops.sync.util;

import com.google.common.collect.ImmutableList;
import hudson.ExtensionList;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.Run;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.exception.PipelineException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jenkins.model.ParameterizedJobMixIn;
import jenkins.scm.api.SCMRevisionAction;
import org.jenkinsci.plugins.workflow.cps.CpsFlowExecution;
import org.jenkinsci.plugins.workflow.cps.replay.OriginalLoadedScripts;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayCause;
import org.jenkinsci.plugins.workflow.flow.FlowExecution;
import org.jenkinsci.plugins.workflow.flow.FlowExecutionOwner;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ReplayUtils {

  private static final Logger logger = LoggerFactory.getLogger(ReplayUtils.class);

  /**
   * Replay a pipeline job base on another one which store in the metadata labels
   *
   * @param job is a pipeline job of Jenkins
   * @param pipelineConfigUID is the uid of PipelineConfig
   * @param currentPipeline the current pipeline
   * @param originalPipeline the original pipeline
   */
  public static void replayJob(
      WorkflowJob job,
      String pipelineConfigUID,
      V1alpha1Pipeline currentPipeline,
      V1alpha1Pipeline originalPipeline)
      throws PipelineException {

    String namespace = currentPipeline.getMetadata().getNamespace();
    String currentPipelineName = currentPipeline.getMetadata().getName();

    if (JenkinsUtils.hasBuildRunningOrCompleted(job, currentPipeline)) {
      logger.info(
          "Pipeline '{}/{}' is running or completed, won't replay again",
          namespace,
          currentPipelineName);
      return;
    }

    WorkflowRun originalRun = JenkinsUtils.getRun(job, originalPipeline);
    if (originalRun == null) {
      V1ObjectMeta originalMeta = originalPipeline.getMetadata();
      throw new PipelineException(
          String.format(
              "Cannot find the original run of pipeline %s/%s",
              originalMeta.getNamespace(), originalMeta.getName()));
    }

    List<Action> actions = new ArrayList<>();
    CpsFlowExecution execution = ReplayUtils.getExecution(originalRun);
    if (execution == null) {
      throw new PipelineException(
          "Cannot get CpsFlowExecution from the originalRun " + originalRun);
    }

    try {
      actions.add(getReplayFlowFactoryAction(execution));
    } catch (Throwable e) {
      throw new PipelineException("Cannot get ReplayFlowFactoryAction", e);
    }

    actions.add(
        new CauseAction(
            new Cause.UserIdCause(),
            getReplayCause(originalRun),
            new JenkinsPipelineCause(currentPipeline, pipelineConfigUID)));

    for (Class<? extends Action> c : COPIED_ACTIONS) {
      actions.addAll(originalRun.getActions(c));
    }

    try {
      logger.debug(
          "Ready to replay {} for Pipeline {}/{}",
          originalRun.getParent(),
          namespace,
          currentPipelineName);

      Queue.Item item =
          ParameterizedJobMixIn.scheduleBuild2(
              originalRun.getParent(), 0, actions.toArray(new Action[0]));
      logger.debug("Replayed Pipeline '{}/{}' action result {}", namespace, currentPipeline, item);

      if (item == null) {
        throw new PipelineException("Unable to schedule a replay, build might be not replayable");
      }

    } catch (Exception e) {
      throw new PipelineException("Trigger the replay build failure", e);
    }
  }

  private static Action getReplayFlowFactoryAction(CpsFlowExecution execution) {
    try {
      Class<?> replayFlowFactoryActionCls =
          Class.forName("org.jenkinsci.plugins.workflow.cps.replay.ReplayFlowFactoryAction");

      Constructor<?> constructor =
          replayFlowFactoryActionCls.getDeclaredConstructor(String.class, Map.class, boolean.class);

      constructor.setAccessible(true);
      return (Action)
          constructor.newInstance(
              execution.getScript(), getOriginalLoadedScripts(execution), execution.isSandbox());
    } catch (Exception e) {
      logger.error("cannot get ReplayFlowFactoryAction", e);
    }

    return null;
  }

  private static ReplayCause getReplayCause(Run<?, ?> original) {
    try {
      Constructor<ReplayCause> constructor = ReplayCause.class.getDeclaredConstructor(Run.class);

      constructor.setAccessible(true);
      return constructor.newInstance(original);
    } catch (Exception e) {
      logger.error("cannot get ReplayCause", e);
    }
    return null;
  }

  private static Map<String, String> getOriginalLoadedScripts(CpsFlowExecution execution) {
    if (execution == null) { // ?
      return Collections.emptyMap();
    }
    Map<String, String> scripts = new TreeMap<>();
    for (OriginalLoadedScripts replayer : ExtensionList.lookup(OriginalLoadedScripts.class)) {
      scripts.putAll(replayer.loadScripts(execution));
    }
    return scripts;
  }

  private static CpsFlowExecution getExecution(WorkflowRun run) throws PipelineException {
    FlowExecutionOwner owner = run.asFlowExecutionOwner();
    FlowExecution exec = owner.getOrNull();
    if (exec == null) {
      try {
        logger.info(
            "Cannot get the execution of run {}, try to trigger the lazy-load of execution",
            run.getFullDisplayName());
        // if execution is null we will trigger the lazy-load of execution and we will wait at most
        // 5 seconds here.
        CompletableFuture.runAsync(run::getExecution).get(5L, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        logger.warn("Error when get execution of original run {}, thread interrupted", run.getFullDisplayName());
        Thread.currentThread().interrupt();
      } catch (ExecutionException e) {
        throw new PipelineException(
            String.format("Failed to get execution of original run %s", run.getFullDisplayName()),
            e);
      } catch (TimeoutException e) {
        throw new PipelineException(
            String.format(
                "Timeout when loading the execution of original run %s", run.getFullDisplayName()),
            e);
      }
      // get execution again after triggering the laze-load
      exec = owner.getOrNull();
    }

    return exec instanceof CpsFlowExecution ? (CpsFlowExecution) exec : null;
  }

  private static final Iterable<Class<? extends Action>> COPIED_ACTIONS =
      ImmutableList.of(ParametersAction.class, SCMRevisionAction.class);
}
