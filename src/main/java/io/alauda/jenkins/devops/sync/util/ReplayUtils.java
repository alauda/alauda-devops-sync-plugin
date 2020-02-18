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
import io.kubernetes.client.models.V1ObjectMeta;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
   * @param job               is a pipeline job of Jenkins
   * @param pipelineConfigUID is the uid of PipelineConfig
   * @param currentPipeline   the current pipeline
   * @param originalPipeline  the original pipeline
   * @return true, if there's no any error
   */
  public static void replayJob(WorkflowJob job, String pipelineConfigUID,
      V1alpha1Pipeline currentPipeline, V1alpha1Pipeline originalPipeline)
      throws PipelineException {
    WorkflowRun originalRun = JenkinsUtils.getRun(job, originalPipeline);
    if (originalRun == null) {
      V1ObjectMeta originalMeta = originalPipeline.getMetadata();
      throw new PipelineException(
          String.format("cannot find the original run of pipeline %s/%s",
              originalMeta.getNamespace(), originalMeta.getName()));
    }

    List<Action> actions = new ArrayList<>();
    CpsFlowExecution execution = ReplayUtils.getExecution(originalRun);
    if (execution == null) {
      throw new PipelineException(
          "cannot get CpsFlowExecution from the originalRun " + originalRun);
    }

    logger.debug("CpsFlowExecution " + execution);

    try {
      actions.add(getReplayFlowFactoryAction(execution));
    } catch (Throwable e) {
      throw new PipelineException("Cannot get ReplayFlowFactoryAction", e);
    }

    logger.debug("actions with ReplayFlowFactoryAction " + actions);

    actions.add(
        new CauseAction(
            new Cause.UserIdCause(),
            getReplayCause(originalRun),
            new JenkinsPipelineCause(currentPipeline, pipelineConfigUID)));

    logger.debug("actions with CauseAction " + actions);

    for (Class<? extends Action> c : COPIED_ACTIONS) {
      actions.addAll(originalRun.getActions(c));

      logger.debug("actions with COPIED_ACTIONS " + actions);
    }

    try {
      logger.debug("ready to replay " + originalRun.getParent());

      Queue.Item item =
          ParameterizedJobMixIn.scheduleBuild2(
              originalRun.getParent(), 0, actions.toArray(new Action[0]));
      logger.debug("replay action result " + item);

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

  private static CpsFlowExecution getExecution(Run run) {
    FlowExecutionOwner owner = ((FlowExecutionOwner.Executable) run).asFlowExecutionOwner();
    if (owner == null) {
      logger.error("cannot get FlowExecutionOwner from run " + run);
      return null;
    } else {
      logger.debug("get FlowExecutionOwner " + owner + " from run " + run);
      logger.debug("get FlowExecution " + owner.getOrNull());
    }
    FlowExecution exec = owner.getOrNull();
    return exec instanceof CpsFlowExecution ? (CpsFlowExecution) exec : null;
  }

  private static final Iterable<Class<? extends Action>> COPIED_ACTIONS =
      ImmutableList.of(ParametersAction.class, SCMRevisionAction.class);
}
