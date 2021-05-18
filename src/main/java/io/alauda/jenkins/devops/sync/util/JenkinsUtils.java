/*
 * Copyright (C) 2018 Alauda.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.util;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.RevisionParameterAction;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import io.alauda.devops.java.client.models.*;
import io.alauda.jenkins.devops.sync.*;
import io.alauda.jenkins.devops.sync.action.AlaudaQueueAction;
import io.alauda.jenkins.devops.sync.event.EventAction;
import io.alauda.jenkins.devops.sync.event.EventParam;
import io.alauda.jenkins.devops.sync.event.EventType;
import io.alauda.jenkins.devops.sync.exception.PipelineException;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.MultiBranchProject;
import org.apache.commons.collections4.CollectionUtils;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** @author suren */
public abstract class JenkinsUtils {

  private static final Logger logger = LoggerFactory.getLogger(JenkinsUtils.class);
  private static final String PARAM_FROM_ENV_DESCRIPTION =
      "From Alauda DevOps PipelineConfig Parameter";

  private JenkinsUtils() {}

  @SuppressFBWarnings
  public static Map<String, ParameterDefinition> addJobParamForPipelineParameters(
      WorkflowJob job, V1alpha1PipelineConfig pipelineConfig, boolean replaceExisting)
      throws IOException {
    List<V1alpha1PipelineParameter> params = pipelineConfig.getSpec().getParameters();

    // get existing property defs, including any manually added from the
    // jenkins console independent of PC
    ParametersDefinitionProperty jenkinsParams =
        job.removeProperty(ParametersDefinitionProperty.class);

    Map<String, ParameterDefinition> paramMap = null;
    if (params != null && params.size() > 0) {
      // build list of current env var names for possible deletion of env
      // vars currently stored
      // as job params
      // builds a list of job parameters

      List<String> envKeys = new ArrayList<>();
      for (V1alpha1PipelineParameter parameter : params) {
        envKeys.add(parameter.getName());
      }
      paramMap = new HashMap<>();
      // store any existing parameters in map for easy key lookup
      if (jenkinsParams != null
          && !PipelineConfigUtils.isTemplatePipeline(pipelineConfig)
          && !PipelineConfigUtils.isGraphPipeline(pipelineConfig)) {
        List<ParameterDefinition> existingParamList = jenkinsParams.getParameterDefinitions();
        for (ParameterDefinition param : existingParamList) {
          // if a user supplied param, add
          if (param.getDescription() == null
              || !param.getDescription().equals(PARAM_FROM_ENV_DESCRIPTION)) {
            paramMap.put(param.getName(), param);
          } else if (envKeys.contains(param.getName())) {
            // the env var still exists on the PipelineConfig side so
            // keep
            paramMap.put(param.getName(), param);
          }
        }
      }

      for (V1alpha1PipelineParameter param : params) {
        ParameterDefinition jenkinsParam = null;
        switch (param.getType()) {
          case PIPELINE_PARAMETER_TYPE_STRING_DEF:
          case PIPELINE_PARAMETER_TYPE_STRING:
            jenkinsParam =
                new StringParameterDefinition(
                    param.getName(), param.getValue(), param.getDescription());
            break;
          case PIPELINE_PARAMETER_TYPE_BOOLEAN_DEF:
          case PIPELINE_PARAMETER_TYPE_BOOLEAN:
            jenkinsParam =
                new BooleanParameterDefinition(
                    param.getName(),
                    Boolean.parseBoolean(param.getValue()),
                    param.getDescription());
            break;
          default:
            logger.warn("Parameter type `{}` is not supported.. skipping...", param.getType());
            break;
        }

        if (jenkinsParam == null) {
          continue;
        }
        // TODO: This is made differently from the original source
        // Need revisit this part if the parameters
        if (replaceExisting || !paramMap.containsKey(jenkinsParam.getName())) {
          paramMap.put(jenkinsParam.getName(), jenkinsParam);
        }
      }

      List<ParameterDefinition> newParamList = new ArrayList<>(paramMap.values());
      job.addProperty(new ParametersDefinitionProperty(newParamList));
    }

    // force save here ... seen some timing issues with concurrent job updates and run initiations
    job.save();
    return paramMap;
  }

  /**
   * Override job's triggers
   *
   * @param job Workflow Job need to add triggers
   * @param triggers trigger
   * @return the exception list
   */
  @Nonnull
  public static List<ANTLRException> setJobTriggers(
      @Nonnull WorkflowJob job, List<V1alpha1PipelineTrigger> triggers) throws IOException {
    List<ANTLRException> exceptions = new ArrayList<>();
    if (CollectionUtils.isEmpty(triggers)) {
      return exceptions;
    }

    job.removeProperty(PipelineTriggersJobProperty.class);
    logger.info(
        "Adding trigger to Job '{}' trigger count is {}", job.getFullName(), triggers.size());

    for (V1alpha1PipelineTrigger pipelineTrigger : triggers) {
      Trigger trigger = null;
      final String type = pipelineTrigger.getType();
      if (type == null) {
        continue;
      }

      switch (type) {
        case PIPELINE_TRIGGER_TYPE_CODE_CHANGE:
          V1alpha1PipelineTriggerCodeChange codeTrigger = pipelineTrigger.getCodeChange();

          if (codeTrigger == null || !codeTrigger.getEnabled()) {
            logger.warn(
                "Trigger type `{}` has empty description or is disabled...",
                PIPELINE_TRIGGER_TYPE_CODE_CHANGE);
            break;
          }

          try {
            trigger = new SCMTrigger(codeTrigger.getPeriodicCheck());

            logger.info("Add CodeChangeTrigger.");
          } catch (ANTLRException exc) {
            logger.error(
                "Error processing trigger type {}, error: {}",
                PIPELINE_TRIGGER_TYPE_CODE_CHANGE,
                exc);
            exceptions.add(exc);
          }

          break;
        case PIPELINE_TRIGGER_TYPE_CRON:
          V1alpha1PipelineTriggerCron cronTrigger = pipelineTrigger.getCron();
          if (cronTrigger == null || !cronTrigger.getEnabled()) {
            logger.warn(
                "Trigger type `{}` has empty description or is disabled...",
                PIPELINE_TRIGGER_TYPE_CRON);
            break;
          }

          try {
            trigger = new TimerTrigger(cronTrigger.getRule());

            logger.info("Add CronTrigger.");
          } catch (ANTLRException exc) {
            logger.error(
                "Error processing trigger type {}, error: {}", PIPELINE_TRIGGER_TYPE_CRON, exc);
            exceptions.add(exc);
          }

          break;
        default:
          logger.warn(
              "Trigger type `{}` is not supported... skipping...", pipelineTrigger.getType());
      }

      if (trigger != null) {
        job.addTrigger(trigger);
      }
    }

    logger.info("Job trigger save done.");

    return exceptions;
  }

  private static void addJobRunParamsFromEnvAndUIParams(
      List<V1alpha1PipelineParameter> pipelineParameters, List<Action> buildActions) {
    if (buildActions == null || pipelineParameters == null) {
      return;
    }

    List<ParameterValue> envVarList = getParameterValues(pipelineParameters);
    if (envVarList.size() == 0) {
      return;
    }

    buildActions.add(new ParametersAction(envVarList));
  }

  @Nonnull
  private static List<ParameterValue> getParameterValues(
      List<V1alpha1PipelineParameter> pipelineParameters) {
    List<ParameterValue> envVarList = new ArrayList<>();
    if (pipelineParameters == null) {
      return envVarList;
    }

    for (V1alpha1PipelineParameter pipeParam : pipelineParameters) {
      ParameterValue paramValue = null;
      String type = pipeParam.getType();
      if (type == null) {
        continue;
      }

      switch (type) {
        case PIPELINE_PARAMETER_TYPE_STRING_DEF:
        case PIPELINE_PARAMETER_TYPE_STRING:
          paramValue =
              new StringParameterValue(
                  pipeParam.getName(), pipeParam.getValue(), pipeParam.getDescription());
          break;
        case PIPELINE_PARAMETER_TYPE_BOOLEAN_DEF:
        case PIPELINE_PARAMETER_TYPE_BOOLEAN:
          paramValue =
              new BooleanParameterValue(
                  pipeParam.getName(),
                  Boolean.parseBoolean(pipeParam.getValue()),
                  pipeParam.getDescription());
          break;
        default:
          logger.warn("Parameter type `{}` is not supported.. skipping...", pipeParam.getType());
          break;
      }

      if (paramValue != null) {
        envVarList.add(paramValue);
      }
    }

    return envVarList;
  }

  public static void triggerJob(@Nonnull WorkflowJob job, @Nonnull V1alpha1Pipeline pipeline)
      throws IOException, PipelineException {
    final V1ObjectMeta pipMeta = pipeline.getMetadata();
    final String namespace = pipMeta.getNamespace();
    final String pipelineName = pipMeta.getName();
    logger.info("Starting trigger pipeline '{}/{}'", namespace, pipelineName);

    if (hasBuildRunningOrCompleted(job, pipeline)) {
      logger.info("Pipeline '{}/{}' is running or completed", namespace, pipelineName);
      return;
    }

    AlaudaJobProperty pcProp = job.getProperty(WorkflowJobProperty.class);
    if (pcProp == null) {
      if (job.getParent() instanceof WorkflowMultiBranchProject) {
        pcProp =
            ((WorkflowMultiBranchProject) job.getParent())
                .getProperties()
                .get(MultiBranchProperty.class);
      }
    }

    if (pcProp == null) {
      throw new PipelineException(
          "Unable to trigger build, reason: job missed AlaudaJobProperty, it may not create by our platform");
    }

    // We need to ensure that we do not remove
    // existing Causes from a Run since other
    // plugins may rely on them.
    List<Cause> newCauses = new ArrayList<>();
    newCauses.add(new JenkinsPipelineCause(pipeline, pcProp.getUid()));
    CauseAction originalCauseAction = PipelineToActionMapper.removeCauseAction(pipelineName);
    if (originalCauseAction != null) {
      if (logger.isDebugEnabled()) {
        logger.debug("Adding existing causes...");
        for (Cause c : originalCauseAction.getCauses()) {
          logger.debug("original cause {}", c);
        }
      }
      newCauses.addAll(originalCauseAction.getCauses());
      if (logger.isDebugEnabled()) {
        for (Cause c : newCauses) {
          logger.debug("new cause {}", c);
        }
      }
    }

    List<Action> pipelineActions = new ArrayList<>();
    pipelineActions.add(new CauseAction(newCauses));
    pipelineActions.add(new AlaudaQueueAction(namespace, pipelineName));
    pipelineActions.addAll(getEventTriggerAction(pipeline));

    V1alpha1PipelineSourceGit sourceGit = pipeline.getSpec().getSource().getGit();
    String commit = null;
    if (pipMeta.getAnnotations() != null
        && pipMeta
            .getAnnotations()
            .containsKey(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT.get().toString())) {
      commit = pipMeta.getAnnotations().get(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT.get().toString());
    }

    if (sourceGit != null && commit != null) {
      try {
        URIish repoURL = new URIish(sourceGit.getUri());
        pipelineActions.add(new RevisionParameterAction(commit, repoURL));
      } catch (URISyntaxException e) {
        throw new PipelineException(
            String.format("Failed to parse git repo URL %s", sourceGit.getUri()), e);
      }
    }

    // params added by user in jenkins ui
    PipelineToActionMapper.removeParameterAction(pipelineName);
    addJobRunParamsFromEnvAndUIParams(pipeline.getSpec().getParameters(), pipelineActions);

    Action[] actionArray;
    if (pipelineActions.size() == 0) {
      actionArray = new Action[] {};
    } else {
      actionArray = pipelineActions.toArray(new Action[0]);
    }

    QueueTaskFuture<WorkflowRun> queueTaskFuture = job.scheduleBuild2(0, actionArray);
    if (queueTaskFuture == null) {
      throw new PipelineException("Unable to schedule build, this job may not be buildable");
    }

    // TODO should offer a better solution
    // TODO should we add an extension point here?
    if (job.getParent() instanceof MultiBranchProject) {
      BranchProjectFactory factory = ((MultiBranchProject) job.getParent()).getProjectFactory();

      SCMRevisionAction revisionAction = null;
      for (Action action : actionArray) {
        if (action instanceof CauseAction) {
          List<Cause> causes = ((CauseAction) action).getCauses();
          if (causes != null) {
            for (Cause cause : causes) {
              if (cause instanceof SCMRevisionAction) {
                revisionAction = (SCMRevisionAction) cause;
                break;
              }
            }
          }
        }
      }

      if (revisionAction != null) {
        factory.setRevisionHash(job, revisionAction.getRevision());
      }
    }

    // If builds are queued too quickly, Jenkins can add the cause
    // to the previous queued pipeline so let's add a tiny
    // sleep.
    try {
      TimeUnit.MILLISECONDS.sleep(50);
    } catch (InterruptedException e) {
      logger.error("updatePipelinePhase Interrupted: {}", e.getMessage());
      Thread.currentThread().interrupt();
    }
  }

  /**
   * getEventTriggerAction will return a list of EventTriggerAction by pipeline. If pipeline wasn't
   * triggered by a event, it will return a empty list.
   *
   * @param pipeline the triggered pipeline
   * @return list of EventTriggerAction
   */
  @Nonnull
  //  private static List<Action> getEventTriggerAction(V1alpha1Pipeline pipeline) {
  //    List<Action> triggerActions = new LinkedList<>();
  //
  //    V1alpha1PipelineCause pipelineCause = pipeline.getSpec().getCause();
  //    if (pipelineCause == null) {
  //      return triggerActions;
  //    }
  //
  //    if (pipelineCause.getType().equals(PIPELINE_CAUSE_TYPE_EVENT_CODE_PUSH)) {
  //      V1alpha1CodeTriggerParameter codeTriggerParameter =
  // pipelineCause.getCodeTriggerParameter();
  //      if (codeTriggerParameter == null) {
  //        return triggerActions;
  //      }
  //
  //      Map<EventParam, String> params = new HashMap<>();
  //      params.put(EventParam.CODE_REPO_EVENT_BRANCH, codeTriggerParameter.getBranch());
  //      params.put(EventParam.CODE_REPO_EVENT_REPO_NAME, codeTriggerParameter.getRepoName());
  //      params.put(
  //          EventParam.CODE_REPO_EVENT_REPO_NAMESPACE, codeTriggerParameter.getRepoNamespace());
  //
  //      EventAction eventAction = new EventAction(EventType.CodeRepoPush, params);
  //      triggerActions.add(eventAction);
  //    }
  //
  //    return triggerActions;
  //  }

  private static List<Action> getEventTriggerAction(V1alpha1Pipeline pipeline)
      throws PipelineException {
    List<Action> triggerActions = new LinkedList<>();

    V1alpha1PipelineCause pipelineCause = pipeline.getSpec().getCause();
    if (pipelineCause == null) {
      return triggerActions;
    }

    EventAction eventAction = null;
    Map<EventParam, String> params = new HashMap<>();
    V1alpha1CodeTriggerParameter codeTriggerParameter = pipelineCause.getCodeTriggerParameter();
    switch (pipelineCause.getType()) {
      case PIPELINE_CAUSE_TYPE_EVENT_CODE_PUSH:
        if (codeTriggerParameter == null) {
          return triggerActions;
        }

        params.put(EventParam.CODE_REPO_EVENT_BRANCH, codeTriggerParameter.getBranch());
        params.put(EventParam.CODE_REPO_EVENT_REPO_NAME, codeTriggerParameter.getRepoName());
        params.put(
            EventParam.CODE_REPO_EVENT_REPO_NAMESPACE, codeTriggerParameter.getRepoNamespace());

        eventAction = new EventAction(EventType.CodeRepoPush, params);
        triggerActions.add(eventAction);
        break;
      case PIPELINE_CAUSE_TYPE_EVENT_TAG_PUSH:
        if (codeTriggerParameter == null) {
          return triggerActions;
        }

        params.put(EventParam.CODE_REPO_EVENT_BRANCH, codeTriggerParameter.getBranch());
        params.put(EventParam.CODE_REPO_EVENT_REPO_NAME, codeTriggerParameter.getRepoName());
        params.put(
            EventParam.CODE_REPO_EVENT_REPO_NAMESPACE, codeTriggerParameter.getRepoNamespace());

        eventAction = new EventAction(EventType.TagPush, params);
        triggerActions.add(eventAction);
        break;
      case PIPELINE_CAUSE_TYPE_EVENT_PULL_REQUEST_BRANCH:
      case PIPELINE_CAUSE_TYPE_EVENT_PULL_REQUEST:
        if (codeTriggerParameter == null) {
          return triggerActions;
        }

        String[] sourceAndTarget = codeTriggerParameter.getBranch().split(",");
        if (sourceAndTarget.length < 2) {
          throw new PipelineException("Could not find source and target branch");
        }

        params.put(EventParam.CODE_REPO_EVENT_SOURCE_BRANCH, sourceAndTarget[0]);
        params.put(EventParam.CODE_REPO_EVENT_TARGET_BRANCH, sourceAndTarget[1]);
        params.put(EventParam.CODE_REPO_EVENT_REPO_NAME, codeTriggerParameter.getRepoName());
        params.put(
            EventParam.CODE_REPO_EVENT_REPO_NAMESPACE, codeTriggerParameter.getRepoNamespace());

        eventAction = new EventAction(EventType.PRBranch, params);
        triggerActions.add(eventAction);
        break;
      default:
        break;
    }

    return triggerActions;
  }

  public static boolean hasBuildRunningOrCompleted(WorkflowJob job, V1alpha1Pipeline pipeline) {
    return getRun(job, pipeline) != null;
  }

  public static WorkflowRun getRun(@Nonnull WorkflowJob job, @Nonnull V1alpha1Pipeline pipeline) {
    return getRun(job, pipeline.getMetadata().getUid());
  }

  private static WorkflowRun getRun(@Nonnull WorkflowJob job, @Nonnull String pipelineUid) {
    for (WorkflowRun run : job.getBuilds()) {
      JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
      if (cause != null && cause.getUid().equals(pipelineUid)) {
        return run;
      }
    }
    return null;
  }

  public static synchronized void deleteRun(WorkflowRun run) {
    try {
      logger.info("Deleting run: " + run.toString());
      run.delete();
    } catch (IOException e) {
      logger.warn("Unable to delete run {}, reason: {}", run.getFullDisplayName(), e);
    }
  }

  /**
   * TODO consider gather with other methods
   *
   * @param run is a build of job
   * @return if the job is a multi-branch pipeline
   */
  public static boolean fromMultiBranch(@Nonnull Run run) {
    Job wfJob = run.getParent();
    if (!(wfJob instanceof WorkflowJob)) {
      return false;
    }

    return (wfJob.getParent() instanceof WorkflowMultiBranchProject);
  }
}
