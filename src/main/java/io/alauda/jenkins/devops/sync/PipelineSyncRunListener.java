/**
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
package io.alauda.jenkins.devops.sync;

import com.cloudbees.workflow.rest.external.AtomFlowNodeExt;
import com.cloudbees.workflow.rest.external.FlowNodeExt;
import com.cloudbees.workflow.rest.external.PendingInputActionsExt;
import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.fasterxml.jackson.databind.SerializationFeature;

import hudson.Extension;
import hudson.PluginManager;
import hudson.model.Result;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;
import hudson.triggers.SafeTimerTask;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.KubernetesClientException;
import io.jenkins.blueocean.rest.factory.BlueRunFactory;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import io.jenkins.blueocean.rest.model.BluePipelineStep;
import io.jenkins.blueocean.rest.model.BlueRun;
import io.jenkins.blueocean.rest.model.BlueRun.BlueRunResult;
import jenkins.model.Jenkins;
import jenkins.util.Timer;

import org.apache.commons.httpclient.HttpStatus;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputAction;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStepExecution;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.AlaudaUtils.getCurrentTimestamp;
import static io.alauda.jenkins.devops.sync.Constants.*;
import static io.alauda.jenkins.devops.sync.AlaudaUtils.formatTimestamp;
import static io.alauda.jenkins.devops.sync.AlaudaUtils.getAuthenticatedAlaudaClient;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.util.logging.Level.*;

/**
 * Listens to Jenkins Job build {@link Run} start and stop then ensure there's a
 * suitable {Build} object in OpenShift thats updated correctly with the
 * current status, logsURL and metrics
 */
@Extension
public class PipelineSyncRunListener extends RunListener<Run> {
  private static final Logger logger = Logger
    .getLogger(PipelineSyncRunListener.class.getName());

  private long pollPeriodMs = 1000 * 5;  // 5 seconds
  private long delayPollPeriodMs = 1000; // 1 seconds
  private static final long maxDelay = 30000;

  private transient Set<Run> runsToPoll = new CopyOnWriteArraySet<>();

  private transient AtomicBoolean timerStarted = new AtomicBoolean(false);

  public PipelineSyncRunListener() {
  }

  @DataBoundConstructor
  public PipelineSyncRunListener(long pollPeriodMs) {
    this.pollPeriodMs = pollPeriodMs;
  }

  /**
   * Joins all the given strings, ignoring nulls so that they form a URL with
   * / between the paths without a // if the previous path ends with / and the
   * next path starts with / unless a path item is blank
   *
   * @param strings the sequence of strings to join
   * @return the strings concatenated together with / while avoiding a double
   * // between non blank strings.
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
    return joined.replaceAll("/+", "/").replaceAll("/\\?", "?")
      .replaceAll("/#", "#").replaceAll(":/", "://");
  }

  @Override
  public synchronized void onStarted(Run run, TaskListener listener) {
    if (shouldPollRun(run)) {
      try {
        JenkinsPipelineCause cause = (JenkinsPipelineCause) run.getCause(JenkinsPipelineCause.class);
        if (cause != null) {
          // TODO This should be a link to the Alauda DevOps
          run.setDescription(cause.getShortDescription());
        }
      } catch (IOException e) {
        logger.log(WARNING, "Cannot set build description: " + e);
      }
      if (runsToPoll.add(run)) {
        logger.info("starting polling build " + run.getUrl());
      }
      checkTimerStarted();
    } else {
      logger.fine("not polling polling pipeline " + run.getUrl()
        + " as its not a WorkflowJob");
    }
    super.onStarted(run, listener);
  }

  protected void checkTimerStarted() {
    if (timerStarted.compareAndSet(false, true)) {
      Runnable task = new SafeTimerTask() {
        @Override
        protected void doRun() throws Exception {
          pollLoop();
        }
      };
      Timer.get().scheduleAtFixedRate(task, delayPollPeriodMs, pollPeriodMs,
        TimeUnit.MILLISECONDS);
    }
  }

  @Override
  public synchronized void onCompleted(Run run, @Nonnull TaskListener listener) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onCompleted " + run.getUrl());
      JenkinsUtils.maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onCompleted(run, listener);
  }

  @Override
  public synchronized void onDeleted(Run run) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onDeleted " + run.getUrl());
      JenkinsUtils.maybeScheduleNext(((WorkflowRun) run).getParent());
    }
    super.onDeleted(run);
  }

  @Override
  public synchronized void onFinalized(Run run) {
    if (shouldPollRun(run)) {
      runsToPoll.remove(run);
      pollRun(run);
      logger.info("onFinalized " + run.getUrl());
    }
    super.onFinalized(run);
  }

  protected synchronized void pollLoop() {
    for (Run run : runsToPoll) {
      pollRun(run);
    }
  }

  protected synchronized void pollRun(Run run) {
    if (!(run instanceof WorkflowRun)) {
      throw new IllegalStateException("Cannot poll a non-workflow run");
    }

    RunExt wfRunExt = RunExt.create((WorkflowRun) run);

    // try blue run
    BlueRun blueRun = null;
    try {
      blueRun = BlueRunFactory.getRun(run, null);
    } catch (Throwable t) {
      // use of info is intentional ... in case the blue ocean deps get
      // bumped
      // by another dependency vs. our bumping it explicitly, I want to
      // find out quickly that we need to switch methods again
      logger.log(Level.WARNING, "pollRun", t);
    }

    try {
      upsertPipeline(run, wfRunExt, blueRun);
    } catch (KubernetesClientException e) {
      if (e.getCode() == HttpStatus.SC_UNPROCESSABLE_ENTITY) {
        runsToPoll.remove(run);
        logger.log(WARNING, "Cannot update status: {0}", e.getMessage());
        return;
      }
      throw e;
    }
  }

  private boolean shouldUpdatePipeline(JenkinsPipelineCause cause,
                                       int latestStageNum, int latestNumFlowNodes, StatusExt status) {
    long currTime = TimeUnit.NANOSECONDS.toMillis(System.nanoTime());
    logger.fine(String.format(
      "shouldUpdatePipeline curr time %s last update %s curr stage num %s last stage num %s"
        + "curr flow num %s last flow num %s status %s",
      String.valueOf(currTime),
      String.valueOf(cause.getLastUpdateToAlaudaDevOps()),
      String.valueOf(latestStageNum),
      String.valueOf(cause.getNumStages()),
      String.valueOf(latestNumFlowNodes),
      String.valueOf(cause.getNumFlowNodes()), status.toString()));

    // if we have not updated in maxDelay time, update
    if (currTime > (cause.getLastUpdateToAlaudaDevOps() + maxDelay)) {
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
    if (status != StatusExt.IN_PROGRESS) {
      return true;
    }

    return false;
  }

  private void upsertPipeline(Run run, RunExt wfRunExt, BlueRun blueRun) {
    if (run == null) {
      return;
    }

    JenkinsPipelineCause cause = (JenkinsPipelineCause) run.getCause(JenkinsPipelineCause.class);
    if (cause == null) {
      return;
    }

    String namespace = cause.getNamespace();
//        String namespace = AlaudaUtils.getNamespacefromPodInputs();
//        if (namespace == null)
//            namespace = cause.getNamespace();
    String rootUrl = AlaudaUtils.getJenkinsURL(
      getAuthenticatedAlaudaClient(), namespace);
    String buildUrl = joinPaths(rootUrl, run.getUrl());
    String logsUrl = joinPaths(buildUrl, "/consoleText");
    String logsConsoleUrl = joinPaths(buildUrl, "/console");
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
      if (jenkins != null) {
        PluginManager pluginMgr = jenkins.getPluginManager();
        if (pluginMgr != null) {
          ClassLoader cl = pluginMgr.uberClassLoader;
          if (cl != null) {
            Class weburlbldr = cl
              .loadClass("org.jenkinsci.plugins.blueoceandisplayurl.BlueOceanDisplayURLImpl");
            Constructor ctor = weburlbldr.getConstructor();
            Object displayURL = ctor.newInstance();
            Method getRunURLMethod = weburlbldr.getMethod(
              "getRunURL", hudson.model.Run.class);
            Object blueOceanURI = getRunURLMethod.invoke(
              displayURL, run);
            logsBlueOceanUrl = blueOceanURI.toString();
            logsBlueOceanUrl = logsBlueOceanUrl.replaceAll(
              "http://unconfigured-jenkins-location/", "");
            if (logsBlueOceanUrl.startsWith("http://")
              || logsBlueOceanUrl.startsWith("https://"))
              // still normalize string
              logsBlueOceanUrl = joinPaths("", logsBlueOceanUrl);
            else
              logsBlueOceanUrl = joinPaths(rootUrl,
                logsBlueOceanUrl);
          }
        }
      }
    } catch (Throwable t) {
      if (logger.isLoggable(Level.FINE))
        logger.log(Level.FINE, "upsertPipeline", t);
    }

    Map<String, BlueRunResult> blueRunResults = new HashMap<>();
    PipelineJson pipeJson = new PipelineJson();
    Map<String, PipelineStage> stageMap = new HashMap<>();


    try {
      if (blueRun != null) {
        Iterator<BluePipelineNode> iter = blueRun.getNodes().iterator();
        PipelineStage pipeStage;
        while (iter.hasNext()) {
          BluePipelineNode node = iter.next();

          if (node != null) {

            BlueRunResult result = node.getResult();
            BlueRun.BlueRunState state = node.getStateObj();

            pipeStage = new PipelineStage(
              node.getId(),
              node.getDisplayName(),
              state != null ? state.name() : Constants.JOB_STATUS_NOT_BUILT,
              result != null ? result.name() : Constants.JOB_STATUS_UNKNOWN,
              node.getStartTimeString(),
              node.getDurationInMillis(),
              0L,
              node.getEdges()
            );
            stageMap.put(node.getDisplayName(), pipeStage);
            pipeJson.addStage(pipeStage);


            blueRunResults.put(node.getDisplayName(), node.getResult());
          }
        }
      }
    } catch (Exception e) {
      logger.log(WARNING, "Failed to fetch stages from blue ocean API. " + e, e);

    }
    boolean pendingInput = false;
    if (!wfRunExt.get_links().self.href.matches("^https?://.*$")) {
      wfRunExt.get_links().self.setHref(joinPaths(rootUrl,
        wfRunExt.get_links().self.href));
    }
    int newNumStages = wfRunExt.getStages().size();
    int newNumFlowNodes = 0;
    List<StageNodeExt> validStageList = new ArrayList<StageNodeExt>();
    List<BlueJsonStage> blueStages = new ArrayList<>();
    for (StageNodeExt stage : wfRunExt.getStages()) {
      // the StatusExt.getStatus() cannot be trusted for declarative
      // pipeline;
      // for example, skipped steps/stages will be marked as complete;
      // we leverage the blue ocean state machine to determine this
      BlueRunResult result = blueRunResults.get(stage.getName());
      if (result != null && result == BlueRunResult.NOT_BUILT) {
        logger.info("skipping stage "
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
      if (links.getLog() != null
        && !links.getLog().href.matches("^https?://.*$")) {
        links.getLog().setHref(joinPaths(rootUrl, links.getLog().href));
      }
      newNumFlowNodes = newNumFlowNodes
        + stage.getStageFlowNodes().size();
      for (AtomFlowNodeExt node : stage.getStageFlowNodes()) {
        FlowNodeExt.FlowNodeLinks nodeLinks = node.get_links();
        if (!nodeLinks.self.href.matches("^https?://.*$")) {
          nodeLinks.self.setHref(joinPaths(rootUrl,
            nodeLinks.self.href));
        }
        if (nodeLinks.getLog() != null
          && !nodeLinks.getLog().href.matches("^https?://.*$")) {
          nodeLinks.getLog().setHref(
            joinPaths(rootUrl, nodeLinks.getLog().href));
        }
      }

      StatusExt status = stage.getStatus();
      if (status != null) {
        if (status.equals(StatusExt.PAUSED_PENDING_INPUT)) {
          pendingInput = true;
        }
        PipelineStage pipeStage = stageMap.get(stage.getName());
        if (pipeStage != null) {
          pipeStage.pause_duration_millis = stage.getPauseDurationMillis();
        }

      }
    }
    // override stages in case declarative has fooled base pipeline support
    wfRunExt.setStages(validStageList);

    boolean needToUpdate = this.shouldUpdatePipeline(cause,
      newNumStages, newNumFlowNodes, wfRunExt.getStatus());
    if (!needToUpdate) {
      return;
    }

    String json = null;
    String blueJson = null;

    ObjectMapper blueJsonMapper = new ObjectMapper();
    blueJsonMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    blueJsonMapper.disable(SerializationFeature.FAIL_ON_SELF_REFERENCES);
    try {
      json = new ObjectMapper().writeValueAsString(wfRunExt);
    } catch (JsonProcessingException e) {
      logger.log(SEVERE, "Failed to serialize workflow run. " + e, e);
//      return;
    } catch (Exception e) {
      logger.log(SEVERE, "Failed to serialize workflow run. " + e, e);
    }
    try {
      blueJson = blueJsonMapper.writeValueAsString(pipeJson);
    } catch (Exception e) {
      logger.log(SEVERE, "Failed to serialize blueJson run. " + e, e);
      logger.log(SEVERE, "blueJson data: " + pipeJson);
      blueJson = null;
    }
//    if (blueJson == null) {
//      blueJson = "{\"start_stage_id\":null,\"stages\":[]}";
//    }

    String pendingActionsJson = null;
    if (pendingInput && run instanceof WorkflowRun) {
      pendingActionsJson = getPendingActionsJson((WorkflowRun) run);
    }

    String phase = runToPipelinePhase(run);

    long started = getStartTime(run);
    String startTime = null;
    String completionTime = null;
    String updatedTime = getCurrentTimestamp();
    if (started > 0) {
      startTime = formatTimestamp(started);

      long duration = getDuration(run);
      if (duration > 0) {
        completionTime = formatTimestamp(started + duration);
      }
    }

    logger.log(INFO, "Patching pipeline {0}/{1}: setting phase to {2}",
      new Object[]{cause.getNamespace(), cause.getName(), phase});
    try {
      // TODO: Change back to use edit
      // need to figure out why it doesnt work
      Pipeline pipeline = getAuthenticatedAlaudaClient()
        .pipelines()
        .inNamespace(cause.getNamespace())
        .withName(cause.getName()).get();

      Map<String, String> annotations = pipeline.getMetadata().getAnnotations();
      annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STATUS_JSON, json);
      if (blueJson != null) {
        annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_JSON, blueJson);
      }
      annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI, buildUrl);
      annotations.put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL, logsUrl);
      annotations.put(Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL, logsConsoleUrl);
      annotations.put(Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL, logsBlueOceanUrl);
      pipeline.getMetadata().setAnnotations(annotations);

      // status
      PipelineStatus stats = pipeline.getStatus();
      if (stats == null) {
        stats = new PipelineStatusBuilder().build();
      }
      stats.setPhase(phase);
      stats.setStartedAt(startTime);
      stats.setFinishedAt(completionTime);
      stats.setUpdatedAt(updatedTime);
      PipelineStatusJenkins jenksStats = stats.getJenkins();
      if (jenksStats == null) {
        jenksStats = new PipelineStatusJenkinsBuilder().build();
      }
      jenksStats.setBuild(String.valueOf(getRunNumber(run)));
//      jenksStats.setStages(StringUtils.isNotEmpty(blueJson) ? blueJson : json);
      if (blueJson != null) {
        jenksStats.setStages(blueJson);
      }
      jenksStats.setResult(getRunResult(run));
      jenksStats.setStatus(wfRunExt.getStatus().name());
      stats.setJenkins(jenksStats);
      pipeline.setStatus(stats);
      Pipeline result = getAuthenticatedAlaudaClient()
        .pipelines()
        .inNamespace(namespace)
        .withName(pipeline.getMetadata().getName())
        .replace(pipeline);
      logger.fine("updated pipeline: " + result);

//          DoneablePipeline builder = getAuthenticatedAlaudaClient()
//            .pipelines()
//            .inNamespace(cause.getNamespace())
//            .withName(cause.getName()).edit().editMetadata()
//            .addToAnnotations(
//              ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STATUS_JSON, json)
//            .addToAnnotations(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI,
//              buildUrl)
//            .addToAnnotations(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL,
//              logsUrl)
//            .addToAnnotations(
//              Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL,
//              logsConsoleUrl)
//            .addToAnnotations(
//              Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL,
//              logsBlueOceanUrl).endMetadata();
//
//            String jenkinsNamespace = System.getenv("KUBERNETES_NAMESPACE");
//            if (jenkinsNamespace != null && !jenkinsNamespace.isEmpty()) {
//              builder.editMetadata().addToAnnotations(
//                  ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_NAMESPACE,
//                        jenkinsNamespace).endMetadata();
//            }
//            if (pendingActionsJson != null && !pendingActionsJson.isEmpty()) {
//              builder.editMetadata().addToAnnotations(
//                  ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON,
//                        pendingActionsJson).endMetadata();
//            }
//
//              builder.editStatus().withPhase(phase)
//              .withStartedAt(startTime)
//              .withFinishedAt(completionTime)
//              .withNewJenkins()
//              .withBuild(String.valueOf(getRunNumber(run)))
//              .withStatus(wfRunExt.getStatus().name())
//              .withResult(getRunResult(run))
//              .withStages(json)
//              .endJenkins()
//              .endStatus().done();
    } catch (KubernetesClientException e) {
      if (HTTP_NOT_FOUND == e.getCode()) {
        logger.info("Pipeline not found, deleting jenkins job: "+run);
        runsToPoll.remove(run);
      } else {
        throw e;
      }
    } catch (Exception e) {
      logger.severe("Exception while trying to update pipeline: "+e);
    }

    cause.setNumFlowNodes(newNumFlowNodes);
    cause.setNumStages(newNumStages);
    cause.setLastUpdateToAlaudaDevOps(TimeUnit.NANOSECONDS.toMillis(System
      .nanoTime()));
  }

  // annotate the Build with pending input JSON so consoles can do the
  // Proceed/Abort stuff if they want
  private String getPendingActionsJson(WorkflowRun run) {
    List<PendingInputActionsExt> pendingInputActions = new ArrayList<PendingInputActionsExt>();
    InputAction inputAction = run.getAction(InputAction.class);

    if (inputAction != null) {
      List<InputStepExecution> executions = inputAction.getExecutions();
      if (executions != null && !executions.isEmpty()) {
        for (InputStepExecution inputStepExecution : executions) {
          pendingInputActions.add(PendingInputActionsExt.create(
            inputStepExecution, run));
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

  private String runToPipelinePhase(Run run) {
    if (run != null && !run.hasntStartedYet()) {
      if (run.isBuilding()) {
        return PipelinePhases.RUNNING;
      } else {
        Result result = run.getResult();
        if (result != null) {
          if (result.equals(Result.SUCCESS)) {
            return PipelinePhases.COMPLETE;
          } else if (result.equals(Result.ABORTED)) {
            return PipelinePhases.CANCELLED;
          } else if (result.equals(Result.FAILURE)) {
            return PipelinePhases.FAILED;
          } else if (result.equals(Result.UNSTABLE)) {
            return PipelinePhases.FAILED;
          } else {
            return PipelinePhases.QUEUED;
          }
        }
      }
    }
    return PipelinePhases.PENDING;
  }

  private String getRunResult(Run run) {
    Result result = run.getResult();
    if (result != null) {
      return result.toString();
    }
    return Result.NOT_BUILT.toString();
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

  /**
   * Returns true if we should poll the status of this run
   *
   * @param run the Run to test against
   * @return true if the should poll the status of this build run
   */
  protected boolean shouldPollRun(Run run) {
    return run instanceof WorkflowRun
      && run.getCause(JenkinsPipelineCause.class) != null
      && GlobalPluginConfiguration.isItEnabled();
  }

  public class BlueJsonStage {
    public StageNodeExt stage;
    public BlueRunResult result;
    public List<BluePipelineNode.Edge> edges;
//      public List<BluePipelineStep> steps;


    public BlueJsonStage(StageNodeExt stage, BlueRunResult result, List<BluePipelineNode.Edge> edges, List<BluePipelineStep> steps) {
      this.stage = stage;
      this.result = result;
      this.edges = edges;
//        this.steps = steps;
    }

    /*
    Map<String, BlueRunResult> blueRunResults = new HashMap<>();
        Map<String, List<BluePipelineNode.Edge>> blueRunEdges = new HashMap<>();
        Map<String, List<BluePipelineStep>> blueRunSteps = new HashMap<>();
     */
  }

  public class PipelineJson {
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

  public class PipelineStage {
    public String id;
    public String name;
    public String status;
    public String result;
    public String start_time;
    public Long duration_millis;
    public Long pause_duration_millis;
    public List<BluePipelineNode.Edge> edges;

    public PipelineStage() {
    }

    public PipelineStage(String id, String name, String status, String result, String start_time, Long duration_millis, Long pause_duration_millis, List<BluePipelineNode.Edge> edges) {
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
