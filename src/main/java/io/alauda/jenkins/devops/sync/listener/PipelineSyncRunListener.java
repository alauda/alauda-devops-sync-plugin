/*
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.listener;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static java.util.logging.Level.*;

import com.cloudbees.workflow.rest.external.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.jenkinsci.plugins.badge.action.BadgeAction;
import hudson.Extension;
import hudson.PluginManager;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.triggers.SafeTimerTask;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatus;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatusJenkins;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatusJenkinsBuilder;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import io.jenkins.blueocean.rest.factory.BlueRunFactory;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import io.jenkins.blueocean.rest.model.BluePipelineStep;
import io.jenkins.blueocean.rest.model.BlueRun;
import io.jenkins.blueocean.rest.model.BlueRun.BlueRunResult;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.extended.workqueue.ratelimiter.BucketRateLimiter;
import io.kubernetes.client.models.V1Status;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a suitable {Build}
 * object thats updated correctly with the current status, logsURL and metrics
 */
@Extension
public class PipelineSyncRunListener extends RunListener<Run> {
  private static final Logger logger = Logger.getLogger(PipelineSyncRunListener.class.getName());

  private long pollPeriodMs = 1000L * 5; // 5 seconds

  private transient RateLimitingQueue<Run> runs =
      new DefaultRateLimitingQueue<>(
          Executors.newSingleThreadExecutor(),
          new BucketRateLimiter<>(100, 2, Duration.ofSeconds(1)));

  private transient AtomicBoolean timerStarted = new AtomicBoolean(false);

  @DataBoundConstructor
  public PipelineSyncRunListener() {}

  @Override
  public void onStarted(Run run, TaskListener listener) {
    AlaudaJobProperty property = getAlaudaJobProperty(run);
    if (property != null) {
      runs.add(run);
      logger.info("starting polling build " + run.getUrl());

      checkTimerStarted();

      if (run instanceof WorkflowRun) {
        WorkflowJob job = ((WorkflowRun) run).getParent();
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
    } else {
      logger.fine("not polling polling pipeline " + run.getUrl() + " as its not a WorkflowJob");
    }
  }

  private void checkTimerStarted() {
    // 1 seconds
    long delayPollPeriodMs = 1000;
    if (timerStarted.compareAndSet(false, true)) {
      Timer.get()
          .scheduleAtFixedRate(
              new SafeTimerTask() {
                @Override
                protected void doRun() throws Exception {
                  pollLoop();
                }
              },
              delayPollPeriodMs,
              pollPeriodMs,
              TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public void onCompleted(Run run, @Nonnull TaskListener listener) {
    if (shouldPollRun(run)) {
      runs.add(run);

      logger.fine("onCompleted " + run.getUrl());
      //            JenkinsUtils.maybeScheduleNext(((WorkflowRun) run).getParent());
    } else {
      logger.fine(() -> "this build is not WorkflowRun, " + run);
    }
  }

  @Override
  public synchronized void onDeleted(Run run) {
    if (!shouldPollRun(run)) {
      return;
    }

    JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
    if (cause != null) {
      String namespace = cause.getNamespace();
      String pipelineName = cause.getName();

      V1Status result = PipelineUtils.delete(namespace, pipelineName);

      int buildNum = run.getNumber();
      logger.fine(
          "Delete `Pipeline` result is: "
              + result
              + "; name is: "
              + pipelineName
              + "; buildNum is: "
              + buildNum);
    }

    logger.fine("onDeleted " + run.getUrl());
  }

  @Override
  public synchronized void onFinalized(Run run) {
    if (shouldPollRun(run)) {
      runs.add(run);

      logger.fine("onFinalized " + run.getUrl());
    }
  }

  private void pollLoop() {
    try {
      while (true) {
        Run runToPoll = runs.get();
        try {
          Result result = pollRun(runToPoll);

          boolean shouldPollAgain = false;
          StatusExt status = RunExt.create((WorkflowRun) runToPoll).getStatus();
          switch (status) {
            case IN_PROGRESS:
            case PAUSED_PENDING_INPUT:
              shouldPollAgain = true;
              break;
            case NOT_EXECUTED:
              if (runToPoll.isBuilding()) {
                shouldPollAgain = true;
                runs.add(runToPoll);
              }
              break;
            default:
              break;
          }

          if (shouldPollAgain) {
            if (result.isRequeue()) {
              logger.log(
                  FINE,
                  String.format(
                      "[PipelineSyncRun] Requeue Run %s", runToPoll.getFullDisplayName()));
              runs.addRateLimited(runToPoll);
            } else {
              logger.log(
                  FINE,
                  String.format(
                      "[PipelineSyncRun] The status of run %s is %s, will poll it again after two seconds",
                      runToPoll.getFullDisplayName(), status));
              runs.addAfter(runToPoll, Duration.ofSeconds(2));
            }
          } else {
            if (result.isRequeue()) {
              logger.log(
                  FINE,
                  String.format(
                      "[PipelineSyncRun] Requeue Run %s", runToPoll.getFullDisplayName()));
              runs.addRateLimited(runToPoll);
            } else {
              runs.forget(runToPoll);
            }
          }
        } finally {
          runs.done(runToPoll);
        }
      }
    } catch (InterruptedException e) {
      logger.log(SEVERE, "Unable to poll status of runs, reason %s", e.getMessage());
      Thread.currentThread().interrupt();
    }
  }

  private synchronized Result pollRun(Run run) {

    if (!(run instanceof WorkflowRun)) {
      throw new IllegalStateException("Cannot poll a non-workflow run");
    }

    logger.log(FINE, String.format("Polling run %s", run.getFullDisplayName()));
    RunExt wfRunExt = null;
    try {
      wfRunExt = RunExt.create((WorkflowRun) run);
    } catch (Throwable e) {
      logger.log(
          FINE,
          String.format(
              "Failed to create RunExt for run %s, reason: %s",
              run.getDisplayName(), e.getMessage()));
      new Result(true);
    }

    // try blue run
    BlueRun blueRun;
    try {
      blueRun = BlueRunFactory.getRun(run, null);
    } catch (Throwable t) {
      logger.log(
          Level.WARNING,
          String.format("Unable to poll run %s, reason: %s", run.getDisplayName(), t.getMessage()));

      return new Result(true);
    }

    try {
      return upsertPipeline(run, wfRunExt, blueRun);
    } catch (Exception e) {
      logger.log(WARNING, "Cannot update status: {0}", e.getMessage());
      return new Result(true);
    }
  }

  private boolean shouldUpdatePipeline(
      JenkinsPipelineCause cause, int latestStageNum, int latestNumFlowNodes, StatusExt status) {
    long currTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    logger.fine(
        String.format(
            "shouldUpdatePipeline curr time %s last update %s curr stage num %s last stage num %s"
                + "curr flow num %s last flow num %s status %s",
            String.valueOf(currTime),
            String.valueOf(cause.getLastUpdateToAlaudaDevOps()),
            String.valueOf(latestStageNum),
            String.valueOf(cause.getNumStages()),
            String.valueOf(latestNumFlowNodes),
            String.valueOf(cause.getNumFlowNodes()),
            status.toString()));

    // if we have not updated in maxDelay time, update
    if (currTime > (cause.getLastUpdateToAlaudaDevOps() + pollPeriodMs)) {
      return true;
    }

    // if the num of stages has changed, update
    if (cause.getNumStages() != latestStageNum) {
      return true;
    }

    // if the num of flow nodes has changed, update
    if (cause.getNumFlowNodes() != latestNumFlowNodes) {
      return true;
    }

    // if the run is in some sort of terminal state, update
    if (status != StatusExt.IN_PROGRESS && status != StatusExt.PAUSED_PENDING_INPUT) {
      return true;
    }

    return false;
  }

  private String toBlueJson(@NotNull PipelineJson pipeJson) {
    ObjectMapper blueJsonMapper = new ObjectMapper();
    blueJsonMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    blueJsonMapper.disable(SerializationFeature.FAIL_ON_SELF_REFERENCES);

    try {
      return blueJsonMapper.writeValueAsString(pipeJson);
    } catch (JsonProcessingException e) {
      logger.log(SEVERE, "Failed to serialize blueJson run. " + e, e);
      logger.log(SEVERE, "blueJson data: " + pipeJson);
    }

    return null;
  }

  private Result upsertPipeline(@NotNull Run run, RunExt wfRunExt, BlueRun blueRun) {
    List<Cause> causes = run.getCauses();
    causes.forEach(
        causeItem -> {
          logger.fine(() -> "run " + run + " caused by " + causeItem);
        });
    JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
    if (cause == null) {
      logger.warning("run " + run + " do not have JenkinsPipelineCause");
      return new Result(false);
    }

    String namespace = cause.getNamespace();
    String rootUrl = ""; // TODO should remove this, AlaudaUtils.getJenkinsURL(client, namespace);
    String buildUrl = joinPaths(rootUrl, run.getUrl());
    String logsUrl = joinPaths(buildUrl, "/consoleText");
    String logsConsoleUrl = joinPaths(buildUrl, "/console");

    String viewLogUrl;
    String stagesUrl;
    String stagesLogUrl;
    String stepsUrl;
    String stepsLogUrl;
    if (JenkinsUtils.fromMultiBranch(run)) {
      WorkflowJob wfJob = (WorkflowJob) run.getParent();
      WorkflowMultiBranchProject multiWfJob = (WorkflowMultiBranchProject) wfJob.getParent();
      viewLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/steps/%%d/log/",
              cause.getNamespace(), multiWfJob.getName(), wfJob.getName(), run.number);

      stagesUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/",
              cause.getNamespace(), multiWfJob.getName(), wfJob.getName(), run.number);

      stagesLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/log/",
              cause.getNamespace(), multiWfJob.getName(), wfJob.getName(), run.number);

      stepsLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/log/",
              cause.getNamespace(), multiWfJob.getName(), wfJob.getName(), run.number);

      stepsUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/steps/",
              cause.getNamespace(), multiWfJob.getName(), wfJob.getName(), run.number);
    } else {
      Job wfJob = run.getParent();

      viewLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/steps/%%d/log/",
              cause.getNamespace(), wfJob.getName(), run.number);

      stagesLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/log/",
              cause.getNamespace(), wfJob.getName(), run.number);

      stagesUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/",
              cause.getNamespace(), wfJob.getName(), run.number);

      stepsLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/log/",
              cause.getNamespace(), wfJob.getName(), run.number);

      stepsUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/steps/",
              cause.getNamespace(), wfJob.getName(), run.number);
    }

    String progressiveLogUrl = joinPaths(buildUrl, "/logText/progressiveText");
    String logsBlueOceanUrl = null;
    try {
      // there are utility functions in the blueocean-dashboard plugin
      // which construct
      // the entire blueocean URI; however, attempting to pull that in as
      // a maven dependency was untenable from an injected test
      // perspective;
      // so we are leveraging reflection;
      Jenkins jenkins = Jenkins.getInstance();
      // NOTE, the excessive null checking is to keep `mvn findbugs:gui`
      // quiet
      PluginManager pluginMgr = jenkins.getPluginManager();
      if (pluginMgr != null) {
        ClassLoader cl = pluginMgr.uberClassLoader;
        if (cl != null) {
          Class weburlbldr =
              cl.loadClass("org.jenkinsci.plugins.blueoceandisplayurl.BlueOceanDisplayURLImpl");
          Constructor ctor = weburlbldr.getConstructor();
          Object displayURL = ctor.newInstance();
          Method getRunURLMethod = weburlbldr.getMethod("getRunURL", Run.class);
          Object blueOceanURI = getRunURLMethod.invoke(displayURL, run);
          logsBlueOceanUrl = blueOceanURI.toString();
          logsBlueOceanUrl =
              logsBlueOceanUrl.replaceAll("http://unconfigured-jenkins-location/", "");
        }
      }
    } catch (Throwable t) {
      if (logger.isLoggable(Level.FINE)) logger.log(Level.FINE, "upsertPipeline", t);
    }

    Map<String, BlueRunResult> blueRunResults = new HashMap<>();
    PipelineJson pipeJson = new PipelineJson();
    Map<String, PipelineStage> stageMap = new HashMap<>();

    try {
      if (blueRun != null && blueRun.getNodes() != null) {
        Iterator<BluePipelineNode> iter = blueRun.getNodes().iterator();
        PipelineStage pipeStage;
        while (iter.hasNext()) {
          BluePipelineNode node = iter.next();
          if (node != null) {
            BlueRunResult result = node.getResult();
            BlueRun.BlueRunState state = node.getStateObj();

            pipeStage =
                new PipelineStage(
                    node.getId(),
                    node.getDisplayName(),
                    state != null ? state.name() : Constants.JOB_STATUS_NOT_BUILT,
                    result != null ? result.name() : Constants.JOB_STATUS_UNKNOWN,
                    node.getStartTimeString(),
                    node.getDurationInMillis(),
                    0L,
                    node.getEdges());
            stageMap.put(node.getDisplayName(), pipeStage);
            pipeJson.addStage(pipeStage);

            blueRunResults.put(node.getDisplayName(), node.getResult());
          }
        }
      }
    } catch (Exception e) {
      logger.log(WARNING, "Failed to fetch stages from blue ocean API. " + e, e);
    }

    if (!wfRunExt.get_links().self.href.matches("^https?://.*$")) {
      wfRunExt.get_links().self.setHref(joinPaths(rootUrl, wfRunExt.get_links().self.href));
    }
    int newNumStages = wfRunExt.getStages().size();
    int newNumFlowNodes = 0;
    List<StageNodeExt> validStageList = new ArrayList<>();
    List<BlueJsonStage> blueStages = new ArrayList<>();
    for (StageNodeExt stage : wfRunExt.getStages()) {
      // the StatusExt.getStatus() cannot be trusted for declarative
      // pipeline;
      // for example, skipped steps/stages will be marked as complete;
      // we leverage the blue ocean state machine to determine this
      BlueRunResult result = blueRunResults.get(stage.getName());
      if (result == BlueRunResult.NOT_BUILT) {
        logger.info(
            "skipping stage "
                + stage.getName()
                + " for the status JSON for pipeline run "
                + run.getDisplayName()
                + " because it was not executed (most likely because of a failure in another stage)");
        continue;
      }
      validStageList.add(stage);

      FlowNodeExt.FlowNodeLinks links = stage.get_links();
      if (!links.self.href.matches("^https?://.*$")) {
        links.self.setHref(joinPaths(rootUrl, links.self.href));
      }
      if (links.getLog() != null && !links.getLog().href.matches("^https?://.*$")) {
        links.getLog().setHref(joinPaths(rootUrl, links.getLog().href));
      }
      newNumFlowNodes = newNumFlowNodes + stage.getStageFlowNodes().size();
      for (AtomFlowNodeExt node : stage.getStageFlowNodes()) {
        FlowNodeExt.FlowNodeLinks nodeLinks = node.get_links();
        if (!nodeLinks.self.href.matches("^https?://.*$")) {
          nodeLinks.self.setHref(joinPaths(rootUrl, nodeLinks.self.href));
        }
        if (nodeLinks.getLog() != null && !nodeLinks.getLog().href.matches("^https?://.*$")) {
          nodeLinks.getLog().setHref(joinPaths(rootUrl, nodeLinks.getLog().href));
        }
      }

      StatusExt status = stage.getStatus();
      if (status != null) {
        PipelineStage pipeStage = stageMap.get(stage.getName());
        if (pipeStage != null) {
          pipeStage.pause_duration_millis = stage.getPauseDurationMillis();
        }
      }
    }
    // override stages in case declarative has fooled base pipeline support
    wfRunExt.setStages(validStageList);

    boolean needToUpdate =
        this.shouldUpdatePipeline(cause, newNumStages, newNumFlowNodes, wfRunExt.getStatus());
    if (!needToUpdate) {
      logger.fine("run " + run + " do not need to update.");
      return new Result(true);
    }

    String phase = PipelineUtils.runToPipelinePhase(run);
    long started = getStartTime(run);
    DateTime startTime = null;
    DateTime completionTime = null;
    DateTime updatedTime = DateTime.now();
    if (started > 0) {
      startTime = new DateTime(started, DateTimeZone.getDefault());
      long duration = getDuration(run);
      if (duration > 0) {
        completionTime = new DateTime(started + duration, DateTimeZone.getDefault());
      }
    }

    logger.log(
        INFO,
        "Patching pipeline {0}/{1}: setting phase to {2}",
        new Object[] {cause.getNamespace(), cause.getName(), phase});
    V1alpha1Pipeline pipeline =
        Clients.get(V1alpha1Pipeline.class)
            .lister()
            .namespace(cause.getNamespace())
            .get(cause.getName());
    if (pipeline == null) {
      logger.warning(
          () ->
              String.format(
                  "Pipeline name[%s], namesapce[%s] don't exists",
                  cause.getName(), cause.getNamespace()));
      return new Result(false);
    }
    V1alpha1Pipeline newPipeline = DeepCopyUtils.deepCopy(pipeline);

    String blueJson = toBlueJson(pipeJson);

    Map<String, String> annotations = newPipeline.getMetadata().getAnnotations();

    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI.get().toString(), buildUrl);
    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL.get().toString(), logsUrl);
    annotations.put(
        ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL.get().toString(), logsConsoleUrl);
    annotations.put(
        ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL.get().toString(), logsBlueOceanUrl);
    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_VIEW_LOG.get().toString(), viewLogUrl);
    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES.get().toString(), stagesUrl);
    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_LOG.get().toString(), stagesLogUrl);
    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STEPS.get().toString(), stepsUrl);
    annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STEPS_LOG.get().toString(), stepsLogUrl);
    annotations.put(
        ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PROGRESSIVE_LOG.get().toString(), progressiveLogUrl);
    newPipeline.getMetadata().setAnnotations(annotations);

    badgeHandle(run, annotations);

    V1alpha1PipelineStatus status =
        createPipelineStatus(
            newPipeline, phase, startTime, completionTime, updatedTime, blueJson, run, wfRunExt);
    newPipeline.setStatus(status);

    boolean succeed = Clients.get(V1alpha1Pipeline.class).update(pipeline, newPipeline);
    if (!succeed) {
      logger.fine(
          String.format(
              "Failed updated pipeline: '%s/%s",
              newPipeline.getMetadata().getNamespace(), newPipeline.getMetadata().getName()));
      return new Result(true);
    } else {
      logger.fine(
          String.format(
              "updated pipeline: '%s/%s",
              newPipeline.getMetadata().getNamespace(), newPipeline.getMetadata().getName()));
    }

    cause.setNumFlowNodes(newNumFlowNodes);
    cause.setNumStages(newNumStages);
    cause.setLastUpdateToAlaudaDevOps(TimeUnit.NANOSECONDS.toMillis(System.nanoTime()));
    return new Result(false);
  }

  private void badgeHandle(@NotNull Run run, Map<String, String> annotations) {
    if (annotations == null) {
      return;
    }

    JSONArray jsonArray = new JSONArray();

    List<? extends Action> actions = run.getAllActions();
    actions
        .stream()
        .filter(action -> action instanceof BadgeAction)
        .forEach(
            action -> {
              BadgeAction badgeAction = (BadgeAction) action;

              JSONObject jsonObject = new JSONObject();
              jsonObject.put("id", badgeAction.getId());
              jsonObject.put("text", badgeAction.getText());
              jsonObject.put("displayName", badgeAction.getDisplayName());
              jsonObject.put("iconPath", badgeAction.getIconPath());
              jsonObject.put("iconFileName", badgeAction.getIconFileName());
              jsonObject.put("link", badgeAction.getLink());
              jsonObject.put("isTextOnly", badgeAction.isTextOnly());

              jsonArray.add(jsonObject);
            });
    annotations.put(ANNOTATION_BADGE.get().toString(), jsonArray.toString());
  }

  private V1alpha1PipelineStatus createPipelineStatus(
      V1alpha1Pipeline pipeline,
      String phase,
      DateTime startTime,
      DateTime completionTime,
      DateTime updatedTime,
      String blueJson,
      Run run,
      RunExt wfRunExt) {
    V1alpha1PipelineStatus status = pipeline.getStatus();
    if (status == null) {
      status = new V1alpha1PipelineStatus();
    }
    status.setPhase(phase);
    status.setStartedAt(startTime);
    status.setFinishedAt(completionTime);
    status.setUpdatedAt(updatedTime);

    V1alpha1PipelineStatusJenkins statusJenkins = status.getJenkins();
    if (statusJenkins == null) {
      statusJenkins = new V1alpha1PipelineStatusJenkinsBuilder().build();
    }
    status.setJenkins(statusJenkins);

    statusJenkins.setBuild(String.valueOf(getRunNumber(run)));
    if (blueJson != null) {
      statusJenkins.setStages(blueJson);
    }

    statusJenkins.setResult(getRunResult(run));
    statusJenkins.setStatus(wfRunExt.getStatus().name());

    return status;
  }

  // annotate the Build with pending input JSON so consoles can do the
  // Proceed/Abort stuff if they want
  private String getPendingActionsJson(WorkflowRun run)
      throws TimeoutException, InterruptedException {
    List<PendingInputActionsExt> pendingInputActions = new ArrayList<PendingInputActionsExt>();
    InputAction inputAction = run.getAction(InputAction.class);

    if (inputAction != null) {
      List<InputStepExecution> executions = inputAction.getExecutions();
      if (executions != null && !executions.isEmpty()) {
        for (InputStepExecution inputStepExecution : executions) {
          pendingInputActions.add(PendingInputActionsExt.create(inputStepExecution, run));
        }
      }
    }
    try {
      return new ObjectMapper().writeValueAsString(pendingInputActions);
    } catch (JsonProcessingException e) {
      logger.log(SEVERE, "Failed to serialize pending actions. " + e, e);
      return null;
    }
  }

  private long getStartTime(Run run) {
    return run.getStartTimeInMillis();
  }

  private long getDuration(Run run) {
    return run.getDuration();
  }

  private String getRunResult(Run run) {
    hudson.model.Result result = run.getResult();
    if (result != null) {
      return result.toString();
    }
    return hudson.model.Result.NOT_BUILT.toString();
  }

  private String getRunStatus(Run run) {
    // queued
    if (run.hasntStartedYet()) {
      return JOB_STATUS_QUEUE;
    }
    // running
    if (run.isBuilding()) {
      return JOB_STATUS_RUNNING;
    }
    // if not running, and there is no log update it is finished
    if (!run.isLogUpdated()) {
      return JOB_STATUS_FINISHED;
    }
    // else?
    return JOB_STATUS_SKIPPED;
  }

  private int getRunNumber(Run run) {
    return run.getNumber();
  }

  private AlaudaJobProperty getAlaudaJobProperty(Run run) {
    if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
      return null;
    }

    if (!(run instanceof WorkflowRun)) {
      return null;
    }

    WorkflowJob job = ((WorkflowRun) run).getParent();
    ItemGroup parent = job.getParent();

    if (parent instanceof WorkflowMultiBranchProject) {
      WorkflowMultiBranchProject multiBranchProject = ((WorkflowMultiBranchProject) parent);
      return multiBranchProject.getProperties().get(MultiBranchProperty.class);
    }

    return WorkflowJobUtils.getAlaudaProperty(job);
  }

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   * @return true if the should poll the status of this build run
   */
  private boolean shouldPollRun(Run run) {
    return getAlaudaJobProperty(run) != null;
  }

  /**
   * Joins all the given strings, ignoring nulls so that they form a URL with / between the paths
   * without a // if the previous path ends with / and the next path starts with / unless a path
   * item is blank
   *
   * @param strings the sequence of strings to join
   * @return the strings concatenated together with / while avoiding a double // between non blank
   *     strings.
   */
  public static String joinPaths(String... strings) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < strings.length; i++) {
      sb.append(strings[i]);
      if (i < strings.length - 1) {
        sb.append("/");
      }
    }
    String joined = sb.toString();

    // And normalize it...
    return joined
        .replaceAll("/+", "/")
        .replaceAll("/\\?", "?")
        .replaceAll("/#", "#")
        .replaceAll(":/", "://");
  }

  private static class BlueJsonStage {
    public StageNodeExt stage;
    public BlueRunResult result;
    public List<BluePipelineNode.Edge> edges;

    public BlueJsonStage(
        StageNodeExt stage,
        BlueRunResult result,
        List<BluePipelineNode.Edge> edges,
        List<BluePipelineStep> steps) {
      this.stage = stage;
      this.result = result;
      this.edges = edges;
    }
  }

  private static class PipelineJson {
    public String start_stage_id;
    public List<PipelineStage> stages;

    public PipelineJson() {
      start_stage_id = null;
      stages = new ArrayList<>();
    }

    public void addStage(PipelineStage stage) {
      if (start_stage_id == null) {
        start_stage_id = stage.id;
      }
      stages.add(stage);
    }
  }

  private static class PipelineStage {
    public String id;
    public String name;
    public String status;
    public String result;
    public String start_time;
    public Long duration_millis;
    public Long pause_duration_millis;
    public List<BluePipelineNode.Edge> edges;

    PipelineStage(
        String id,
        String name,
        String status,
        String result,
        String start_time,
        Long duration_millis,
        Long pause_duration_millis,
        List<BluePipelineNode.Edge> edges) {
      this.id = id;
      this.name = name;
      this.status = status;
      this.result = result;
      this.start_time = start_time;
      this.duration_millis = duration_millis;
      this.pause_duration_millis = pause_duration_millis;
      this.edges = edges;
    }
  }
}
