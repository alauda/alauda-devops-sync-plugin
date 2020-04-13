package io.alauda.jenkins.devops.sync.listener;

import static com.cloudbees.workflow.rest.external.StatusExt.ABORTED;
import static com.cloudbees.workflow.rest.external.StatusExt.FAILED;
import static com.cloudbees.workflow.rest.external.StatusExt.NOT_EXECUTED;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PROGRESSIVE_LOG;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_LOG;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STEPS;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STEPS_LOG;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_VIEW_LOG;
import static io.alauda.jenkins.devops.sync.constants.Constants.ANNOTATION_BADGE;
import static io.alauda.jenkins.devops.sync.constants.Constants.CONDITION_STATUS_FALSE;
import static io.alauda.jenkins.devops.sync.constants.Constants.CONDITION_STATUS_TRUE;
import static io.alauda.jenkins.devops.sync.constants.Constants.CONDITION_STATUS_UNKNOWN;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_REASON_CANCELLED;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_REASON_COMPLETE;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_REASON_FAILED;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_REASON_PENDING_INPUT;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_REASON_RUNNING;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_TYPE_COMPLETED;

import com.cloudbees.workflow.rest.external.RunExt;
import com.cloudbees.workflow.rest.external.StageNodeExt;
import com.cloudbees.workflow.rest.external.StatusExt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jenkinsci.plugins.badge.action.BadgeAction;
import hudson.PluginManager;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Action;
import hudson.model.Job;
import hudson.model.Run;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatus;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatusInfo;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatusInfoItem;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatusJenkins;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatusJenkinsBuilder;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.action.PipelineAction;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.exception.PipelineException;
import io.alauda.jenkins.devops.sync.util.ConditionUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import io.jenkins.blueocean.rest.factory.BlueRunFactory;
import io.jenkins.blueocean.rest.model.BluePipelineNode;
import io.jenkins.blueocean.rest.model.BluePipelineNode.Edge;
import io.jenkins.blueocean.rest.model.BlueRun;
import io.jenkins.blueocean.rest.model.BlueRun.BlueRunResult;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.extended.workqueue.ratelimiter.BucketRateLimiter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineSyncExecutor implements Runnable {

  public static final Logger logger = LoggerFactory.getLogger(PipelineSyncExecutor.class);

  private static final int DEFAULT_WORKER_COUNT = 1;
  private static PipelineSyncExecutor instance;

  public static PipelineSyncExecutor getInstance() {
    if (instance == null) {
      synchronized (PipelineSyncExecutor.class) {
        instance = new PipelineSyncExecutor();
      }
    }
    return instance;
  }

  @Initializer(after = InitMilestone.PLUGINS_STARTED)
  @SuppressWarnings("unused")
  @Restricted(DoNotUse.class)
  public static void initializeExecutor() {
    logger.info("Initializing PipelineSyncExecutor...");

    PipelineSyncExecutor executor = getInstance();
    ExecutorService threadPool = Executors.newSingleThreadExecutor();
    threadPool.submit(executor);

    logger.info("PipelineSyncExecutor initialized :P");
  }

  private ScheduledExecutorService executor;
  private RateLimitingQueue<WorkflowRun> runQueue;

  private PipelineSyncExecutor() {
    // TODO increase the default worker count if we can ensure same run are same instance
    executor =
        Executors.newScheduledThreadPool(DEFAULT_WORKER_COUNT, namedRunSyncWorkerThreadFactory());

    runQueue =
        new DefaultRateLimitingQueue<>(
            Executors.newSingleThreadExecutor(),
            new BucketRateLimiter<>(100, 2, Duration.ofSeconds(1)));
  }

  public void submit(WorkflowRun run) {
    runQueue.addRateLimited(run);
  }

  public void submit(V1alpha1Pipeline pipelineNeedSyncTo, V1alpha1PipelineConfig parentPc) {
    String namespace = pipelineNeedSyncTo.getMetadata().getNamespace();
    String pipelineName = pipelineNeedSyncTo.getMetadata().getName();

    WorkflowJob job = JenkinsClient.getInstance().getJob(pipelineNeedSyncTo, parentPc);
    if (job == null) {
      logger.info(
          "Failed to add pipeline '{}/{}' to poll queue, unable to find related job",
          namespace,
          pipelineName);
      return;
    }

    WorkflowRun run = JenkinsUtils.getRun(job, pipelineNeedSyncTo);
    if (run == null) {
      logger.info(
          "Failed to add pipeline '{}/{}' to poll queue, unable to find related run",
          namespace,
          pipelineName);

      return;
    }

    runQueue.addRateLimited(run);
  }

  @Override
  public void run() {
    logger.info(
        "Initializing PipelineSyncExecutor {} workers, worker count {}",
        this,
        DEFAULT_WORKER_COUNT);
    for (int i = 0; i < DEFAULT_WORKER_COUNT; i++) {
      int finalIndex = i;
      executor.scheduleWithFixedDelay(
          () -> {
            logger.info("Starting PipelineSyncWorker {}", finalIndex);
            worker();
            logger.info("Resuming PipelineSyncWorker {}", finalIndex);
          },
          0,
          1,
          TimeUnit.SECONDS);
    }
  }

  private void worker() {
    while (!runQueue.isShuttingDown()) {
      WorkflowRun run = null;
      try {
        run = runQueue.get();
      } catch (InterruptedException e) {
        logger.error("PipelineSyncExecutor worker interrupted.", e);
        Thread.currentThread().interrupt();
      }

      if (run == null) {
        logger.info("PipelineSyncExecutor worker exiting because work queue has shutdown..");
        return;
      }

      try {
        Result result = syncWorkflowRunToPipeline(run);

        StatusExt status = RunExt.create(run).getStatus();
        // In these conditions, we will requeue the run anyway despite what the result returned
        // 1. The build is running
        // 2. The build is waiting for input
        // 3. The build is not executed but not completed.
        if (status.equals(StatusExt.IN_PROGRESS)
            || status.equals(StatusExt.PAUSED_PENDING_INPUT)
            || (status.equals(NOT_EXECUTED) && run.isBuilding())) {
          result.setRequeue(true);
        }

        if (result.isRequeue()) {
          if (result.getRequeueAfter() != null) {
            runQueue.addAfter(run, result.getRequeueAfter());
          } else {
            runQueue.addRateLimited(run);
          }
        } else {
          runQueue.forget(run);
        }
      } catch (Throwable e) {
        logger.error(
            "Failed to sync run {} details to Pipeline, reason {}", run.getFullDisplayName(), e);
      } finally {
        runQueue.done(run);
      }
    }
  }

  /**
   * Sync details of run to Pipeline
   *
   * @param run run need to be synced
   * @return the result
   */
  private Result syncWorkflowRunToPipeline(WorkflowRun run) throws PipelineException {
    logger.debug("Starting to poll WorkflowRun to update Pipeline status");
    Result result = new Result(false);

    JenkinsPipelineCause relatedPipeline = PipelineUtils.findAlaudaCause(run);
    if (relatedPipeline == null) {
      logger.debug(
          "Won't sync run {} to pipeline, it is not bind to Pipeline", run.getFullDisplayName());
      return result;
    }

    String namespace = relatedPipeline.getNamespace();
    String name = relatedPipeline.getName();

    V1alpha1Pipeline pipeline =
        Clients.get(V1alpha1Pipeline.class).lister().namespace(namespace).get(name);

    if (pipeline == null) {
      logger.debug("Won't sync run {} to pipeline, no Pipeline", run.getFullDisplayName());
      return result;
    }

    V1alpha1Pipeline pipelineCopy = DeepCopyUtils.deepCopy(pipeline);

    addURLsToAnnotations(run, pipelineCopy);
    addBadgesToAnnotations(run, pipelineCopy);

    addRunDetailsToStatus(run, pipelineCopy);

    mountActionsPipeline(run.getAllActions(), pipelineCopy);

    boolean succeed = Clients.get(V1alpha1Pipeline.class).update(pipeline, pipelineCopy);
    if (!succeed) {
      logger.debug("Failed updated pipeline: '{}/{}'", namespace, name);
      return result;
    } else {
      logger.debug("updated pipeline: '{}/{}'", namespace, name);
    }

    return result;
  }

  private void addRunDetailsToStatus(WorkflowRun run, V1alpha1Pipeline pipeline)
      throws PipelineException {
    RunExt runExt = RunExt.create(run);
    BlueRun blueRun;
    try {
      blueRun = BlueRunFactory.getRun(run, null);
      if (blueRun == null) {
        throw new PipelineException(
            String.format(
                "Unable to poll run %s, reason: cannot find BlueRun", run.getDisplayName()));
      }
    } catch (Throwable t) {
      throw new PipelineException(String.format("Unable to poll run %s", run.getDisplayName()), t);
    }

    PipelineJson pipelineJson = getBlueOceanStages(runExt, blueRun);
    String blueJson = pipelineJson.toBlueJson();

    long started = run.getStartTimeInMillis();
    DateTime startTime = null;
    DateTime completionTime = null;
    DateTime updatedTime = DateTime.now();
    if (started > 0) {
      startTime = new DateTime(started, DateTimeZone.getDefault());
      long duration = run.getDuration();
      if (duration > 0) {
        completionTime = new DateTime(started + duration, DateTimeZone.getDefault());
      }
    }

    V1alpha1PipelineStatus status = pipeline.getStatus();
    status.setStartedAt(startTime);
    status.setFinishedAt(completionTime);
    status.setUpdatedAt(updatedTime);

    V1alpha1PipelineStatusJenkins statusJenkins = status.getJenkins();
    if (statusJenkins == null) {
      statusJenkins = new V1alpha1PipelineStatusJenkinsBuilder().build();
    }
    status.setJenkins(statusJenkins);

    statusJenkins.setBuild(String.valueOf(run.getNumber()));
    if (blueJson != null) {
      statusJenkins.setStages(blueJson);
    }

    statusJenkins.setResult(getRunResult(run));
    statusJenkins.setStatus(runExt.getStatus().name());

    updateCompletedCond(run, pipeline, runExt);
  }

  private void updateCompletedCond(WorkflowRun run, V1alpha1Pipeline pipeline, RunExt runExt) {
    V1alpha1Condition condition =
        ConditionUtils.getCondition(
            pipeline.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_COMPLETED);
    Objects.requireNonNull(condition);

    StatusExt wfRunStatus = runExt.getStatus();
    if (wfRunStatus.equals(NOT_EXECUTED)) {
      if (run.getResult() == hudson.model.Result.FAILURE
          || run.getResult() == hudson.model.Result.UNSTABLE) {
        wfRunStatus = FAILED;
      } else if (run.getResult() == hudson.model.Result.ABORTED) {
        wfRunStatus = ABORTED;
      }
    }

    switch (wfRunStatus) {
      case NOT_EXECUTED:
        if (run.hasntStartedYet()) {
          condition.setStatus(CONDITION_STATUS_UNKNOWN);
        }
        break;
      case ABORTED:
        condition.setStatus(CONDITION_STATUS_TRUE);
        condition.setReason(PIPELINE_CONDITION_REASON_CANCELLED);
        break;
      case SUCCESS:
        condition.setStatus(CONDITION_STATUS_TRUE);
        condition.setReason(PIPELINE_CONDITION_REASON_COMPLETE);
        break;
      case IN_PROGRESS:
        condition.setStatus(CONDITION_STATUS_FALSE);
        condition.setReason(PIPELINE_CONDITION_REASON_RUNNING);
        break;
      case PAUSED_PENDING_INPUT:
        condition.setStatus(CONDITION_STATUS_FALSE);
        condition.setReason(PIPELINE_CONDITION_REASON_PENDING_INPUT);
        break;
      case FAILED:
      case UNSTABLE:
        condition.setStatus(CONDITION_STATUS_TRUE);
        condition.setReason(PIPELINE_CONDITION_REASON_FAILED);
        break;
    }
    condition.setLastAttempt(DateTime.now());
  }

  private PipelineJson getBlueOceanStages(RunExt runExt, BlueRun blueRun) {
    PipelineJson pipelineJson = new PipelineJson();

    Map<String, BlueRunResult> blueRunResults = new HashMap<>();
    Map<String, PipelineStage> stageMap = new HashMap<>();
    for (BluePipelineNode node : blueRun.getNodes()) {
      BlueRunResult result = node.getResult();
      BlueRun.BlueRunState state = node.getStateObj();

      PipelineStage pipeStage =
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
      pipelineJson.addStage(pipeStage);

      blueRunResults.put(node.getDisplayName(), node.getResult());
    }

    List<StageNodeExt> validStageList = new ArrayList<>();
    for (StageNodeExt stage : runExt.getStages()) {
      // the StatusExt.getStatus() cannot be trusted for declarative
      // pipeline;
      // for example, skipped steps/stages will be marked as complete;
      // we leverage the blue ocean state machine to determine this
      BlueRunResult result = blueRunResults.get(stage.getName());
      if (result == BlueRunResult.NOT_BUILT) {
        continue;
      }
      validStageList.add(stage);

      StatusExt status = stage.getStatus();
      if (status != null) {
        PipelineStage pipeStage = stageMap.get(stage.getName());
        if (pipeStage != null) {
          pipeStage.pause_duration_millis = stage.getPauseDurationMillis();
        }
      }
    }
    // override stages in case declarative has fooled base pipeline support
    runExt.setStages(validStageList);

    return pipelineJson;
  }

  private void addURLsToAnnotations(WorkflowRun run, V1alpha1Pipeline pipeline) {
    String namespace = pipeline.getMetadata().getNamespace();

    String buildUrl = run.getUrl();
    String logsUrl = joinPaths(buildUrl, "/consoleText");
    String logsConsoleUrl = joinPaths(buildUrl, "/console");
    String progressiveLogUrl = joinPaths(buildUrl, "/logText/progressiveText");

    String viewLogUrl;
    String stagesUrl;
    String stagesLogUrl;
    String stepsUrl;
    String stepsLogUrl;
    if (JenkinsUtils.fromMultiBranch(run)) {
      WorkflowJob branchJob = run.getParent();
      WorkflowMultiBranchProject multiBranchProject =
          (WorkflowMultiBranchProject) branchJob.getParent();
      viewLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/steps/%%d/log/",
              namespace, multiBranchProject.getName(), branchJob.getName(), run.number);

      stagesUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/",
              namespace, multiBranchProject.getName(), branchJob.getName(), run.number);

      stagesLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/log/",
              namespace, multiBranchProject.getName(), branchJob.getName(), run.number);

      stepsLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/log/",
              namespace, multiBranchProject.getName(), branchJob.getName(), run.number);

      stepsUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/branches/%s/runs/%d/nodes/%%d/steps/",
              namespace, multiBranchProject.getName(), branchJob.getName(), run.number);
    } else {
      Job wfJob = run.getParent();

      viewLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/steps/%%d/log/",
              namespace, wfJob.getName(), run.number);

      stagesLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/log/",
              namespace, wfJob.getName(), run.number);

      stagesUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/",
              namespace, wfJob.getName(), run.number);

      stepsLogUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/log/",
              namespace, wfJob.getName(), run.number);

      stepsUrl =
          String.format(
              "/blue/rest/organizations/jenkins/pipelines/%s/pipelines/%s/runs/%d/nodes/%%d/steps/",
              namespace, wfJob.getName(), run.number);
    }

    String logsBlueOceanUrl = "";
    try {
      logsBlueOceanUrl = getBlueOceanUrl(run);
    } catch (Exception e) {
      logger.debug("Failed to get BlueOceanUrl, reason {}", e.getMessage());
    }

    Map<String, String> annotations = pipeline.getMetadata().getAnnotations();

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
  }

  @SuppressWarnings("unchecked")
  private String getBlueOceanUrl(WorkflowRun run) throws Exception {
    String logsBlueOceanUrl; // there are utility functions in the blueocean-dashboard plugin
    // which construct
    // the entire blueocean URI; however, attempting to pull that in as
    // a maven dependency was untenable from an injected test
    // perspective;
    // so we are leveraging reflection;
    Jenkins jenkins = Jenkins.get();
    // NOTE, the excessive null checking is to keep `mvn findbugs:gui`
    // quiet
    PluginManager pluginMgr = jenkins.getPluginManager();
    ClassLoader cl = pluginMgr.uberClassLoader;
    if (cl != null) {
      Class weburlbldr =
          cl.loadClass("org.jenkinsci.plugins.blueoceandisplayurl.BlueOceanDisplayURLImpl");
      Constructor ctor = weburlbldr.getConstructor();
      Object displayURL = ctor.newInstance();
      Method getRunURLMethod = weburlbldr.getMethod("getRunURL", Run.class);
      Object blueOceanURI = getRunURLMethod.invoke(displayURL, run);
      logsBlueOceanUrl = blueOceanURI.toString();
      logsBlueOceanUrl = logsBlueOceanUrl.replaceAll("http://unconfigured-jenkins-location/", "");
      return logsBlueOceanUrl;
    }
    throw new PipelineException("Unable to find ClassLoader");
  }

  private void addBadgesToAnnotations(@Nonnull Run run, V1alpha1Pipeline pipeline) {
    Map<String, String> annotations = pipeline.getMetadata().getAnnotations();
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

  private String getRunResult(Run run) {
    hudson.model.Result result = run.getResult();
    if (result != null) {
      return result.toString();
    }
    return hudson.model.Result.NOT_BUILT.toString();
  }

  /** Used to hang action data in the pipeline and provide it to DSL for real-time acquisition. */
  public static synchronized void mountActionsPipeline(
      List<? extends Action> actions, V1alpha1Pipeline pipeline) {
    if (actions == null || pipeline == null) {
      return;
    }
    List<V1alpha1PipelineStatusInfoItem> items = new ArrayList<>();
    actions
        .stream()
        .filter(action -> action.getClass().equals(PipelineAction.class))
        .forEach(
            action -> {
              PipelineAction pa = (PipelineAction) action;
              pa.getItems()
                  .forEach(
                      item -> {
                        V1alpha1PipelineStatusInfoItem data = new V1alpha1PipelineStatusInfoItem();
                        data.setValue(item.getValue());
                        data.setType(item.getType());
                        data.setDescription(item.getDesc());
                        data.setName(item.getName());
                        items.add(data);
                      });
            });
    if (pipeline.getStatus().getInformation() == null) {
      pipeline.getStatus().setInformation(new V1alpha1PipelineStatusInfo());
    }
    pipeline.getStatus().getInformation().setItems(items);
  }

  private static ThreadFactory namedRunSyncWorkerThreadFactory() {
    return new ThreadFactoryBuilder().setNameFormat("PipelineSyncWorker" + "-%d").build();
  }

  private static class PipelineJson {

    String start_stage_id;
    List<PipelineStage> stages;

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

    private String toBlueJson() {
      ObjectMapper blueJsonMapper = new ObjectMapper();
      blueJsonMapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
      blueJsonMapper.disable(SerializationFeature.FAIL_ON_SELF_REFERENCES);

      try {
        return blueJsonMapper.writeValueAsString(this);
      } catch (JsonProcessingException e) {
        logger.error("Failed to serialize blueJson run. ", e);
      }
      return null;
    }
  }

  private static class PipelineStage {

    private String id;
    private String name;
    private String status;
    private String result;
    private String start_time;
    private Long duration_millis;
    private Long pause_duration_millis;
    private List<BluePipelineNode.Edge> edges;

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

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public String getResult() {
      return result;
    }

    public void setResult(String result) {
      this.result = result;
    }

    public String getStart_time() {
      return start_time;
    }

    public void setStart_time(String start_time) {
      this.start_time = start_time;
    }

    public Long getDuration_millis() {
      return duration_millis;
    }

    public void setDuration_millis(Long duration_millis) {
      this.duration_millis = duration_millis;
    }

    public Long getPause_duration_millis() {
      return pause_duration_millis;
    }

    public void setPause_duration_millis(Long pause_duration_millis) {
      this.pause_duration_millis = pause_duration_millis;
    }

    public List<Edge> getEdges() {
      return edges;
    }

    public void setEdges(List<Edge> edges) {
      this.edges = edges;
    }
  }
}
