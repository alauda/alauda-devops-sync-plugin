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
import io.alauda.jenkins.devops.sync.core.ActionResult;
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
import org.jenkinsci.plugins.workflow.cps.replay.ReplayAction;
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
   * Replay a pipeline job and return immediately.
   *
   * @see #replayJob(WorkflowJob, String, V1alpha1Pipeline, V1alpha1Pipeline)
   * @param job is a pipeline job of Jenkins
   * @param pipelineConfigUID is the uid of PipelineConfig
   * @param currentPipeline the current pipeline
   * @param originalPipeline the original pipeline
   * @return ActionResult, if there's no any error
   */
  public static ActionResult replayJobAndReturn(
      WorkflowJob job,
      String pipelineConfigUID,
      V1alpha1Pipeline currentPipeline,
      V1alpha1Pipeline originalPipeline) {
    new Thread(
            () -> {
              for (int i = 0; i < 100; i++) {
                WorkflowRun originalRun = JenkinsUtils.getRun(job, originalPipeline);
                FlowExecution execution = ReplayUtils.getExecution(originalRun);
                if (execution == null) {
                  try {
                    Thread.sleep(1000);
                  } catch (InterruptedException e) {
                    e.printStackTrace();
                  }
                  continue;
                }

                if (ReplayUtils.replayJob(
                    job, pipelineConfigUID, currentPipeline, originalPipeline)) {
                  break;
                }
              }
            })
        .start();
    return ActionResult.SUCCESS();
  }

  /**
   * Replay a pipeline job base on another one which store in the metadata labels
   *
   * @param job is a pipeline job of Jenkins
   * @param pipelineConfigUID is the uid of PipelineConfig
   * @param currentPipeline the current pipeline
   * @param originalPipeline the original pipeline
   * @return ActionResult, if there's no any error
   */
  public static boolean replayJob(
      WorkflowJob job,
      String pipelineConfigUID,
      V1alpha1Pipeline currentPipeline,
      V1alpha1Pipeline originalPipeline) {
    WorkflowRun originalRun = JenkinsUtils.getRun(job, originalPipeline);
    if (originalRun == null) {
      V1ObjectMeta originalMeta = originalPipeline.getMetadata();
      logger.error(
          "cannot find the original run of pipeline %s/%s",
          originalMeta.getNamespace(), originalMeta.getName());
      return false;
    }

    Class<ReplayAction> replayActionCls = ReplayAction.class;
    ReplayAction replayAction = null;
    try {
      Constructor<ReplayAction> constructor = replayActionCls.getDeclaredConstructor(Run.class);
      constructor.setAccessible(true);
      replayAction = constructor.newInstance(originalRun);
    } catch (Exception e) {
      logger.error("cannot get constructor of ReplayAction", e);
    }

    if (replayAction != null) {
      List<Action> actions = new ArrayList<Action>();
      FlowExecution execution = ReplayUtils.getExecution(originalRun);
      if (execution == null) {
        logger.warn("cannot get CpsFlowExecution from the originalRun " + originalRun);
        return false;
      }

      logger.debug("CpsFlowExecution " + execution);

      if (!(execution instanceof CpsFlowExecution)) {
        return false;
      }

      try {
        actions.add(getReplayFlowFactoryAction((CpsFlowExecution) execution));
      } catch (Throwable e) {
        logger.error("cannot get ReplayFlowFactoryAction", e);
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
                originalRun.getParent(), 0, actions.toArray(new Action[actions.size()]));
        logger.debug("replay action result " + item);

        return item != null;
      } catch (Exception e) {
        logger.error("trigger the replay build failure", e);
      }
    } else {
      logger.error("cannot get the instance of ReplayAction");
    }

    return false;
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
      return Collections.<String, String>emptyMap();
    }
    Map<String, String> scripts = new TreeMap<>();
    for (OriginalLoadedScripts replayer : ExtensionList.lookup(OriginalLoadedScripts.class)) {
      scripts.putAll(replayer.loadScripts(execution));
    }
    return scripts;
  }

  private static FlowExecution getExecution(Run run) {
    FlowExecutionOwner owner = ((FlowExecutionOwner.Executable) run).asFlowExecutionOwner();
    if (owner == null) {
      logger.error("cannot get FlowExecutionOwner from run " + run);
      return null;
    } else {
      logger.debug("get FlowExecutionOwner " + owner + " from run " + run);
      logger.debug("get FlowExecution " + owner.getOrNull());
    }
    return owner.getOrNull();
  }

  private static final Iterable<Class<? extends Action>> COPIED_ACTIONS =
      ImmutableList.of(ParametersAction.class, SCMRevisionAction.class);
}
