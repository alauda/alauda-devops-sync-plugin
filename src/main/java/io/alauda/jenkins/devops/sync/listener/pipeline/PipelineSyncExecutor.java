package io.alauda.jenkins.devops.sync.listener.pipeline;

import com.cloudbees.workflow.rest.external.RunExt;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.google.gson.Gson;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Cause;
import hudson.model.Cause.UpstreamCause;
import hudson.plugins.git.Revision;
import hudson.plugins.git.util.BuildData;
import hudson.scm.ChangeLogSet;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.PipelineClient;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineCause;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineCauseReplayProperty;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineCauseType;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineCauseUpstreamProperty;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineDownstream;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineInfoSpec;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineInfoStatus;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineSCMChange;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineSCMInfo;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineSCMInfoBranch;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineSCMInfoPR;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineStatusJenkins;
import io.alauda.jenkins.devops.sync.listener.pipeline.PipelineInfo.PipelineStatusJenkinsArtifact;
import io.alauda.jenkins.devops.sync.multiBranch.PullRequest;
import io.alauda.jenkins.devops.sync.util.PipelineGenerator;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.extended.workqueue.ratelimiter.BucketRateLimiter;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import jenkins.metrics.impl.TimeInQueueAction;
import org.apache.commons.collections4.CollectionUtils;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayCause;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineSyncExecutor implements Runnable {

  private static final Logger logger = LoggerFactory.getLogger(PipelineSyncExecutor.class);

  private static final int DEFAULT_WORKER_COUNT = 1;
  // TODO use annotation store pipeline info for now, need to move it to right place in the feature
  private static final String EXPERIMENTAL_FEATURE_PIPELINE_INFO =
      "alauda.io/experimental-feature-pipeline-info";

  private static volatile PipelineSyncExecutor instance;

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
    // TODO increase the default worker count if we ensure same run are same instance
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
      }

      if (run == null) {
        logger.info("PipelineSyncExecutor worker exiting because work queue has shutdown..");
        return;
      }

      try {
        Result result = syncWorkflowRunToPipeline(run);
        if (result.isRequeue()) {
          if (result.getRequeueAfter() != null) {
            runQueue.addAfter(run, result.getRequeueAfter());
          } else {
            runQueue.addRateLimited(run);
          }
        }
      } catch (Throwable e) {
        logger.error(
            "Failed to sync run {} info to Pipeline, reason {}", run.getFullDisplayName(), e);
      } finally {
        runQueue.done(run);
      }
    }
  }

  private Result syncWorkflowRunToPipeline(WorkflowRun run) {
    JenkinsPipelineCause relatedPipeline = PipelineUtils.findAlaudaCause(run);
    if (relatedPipeline == null) {
      logger.debug(
          "Won't sync run {} to pipeline, it is not bind to PipelineConfig",
          run.getFullDisplayName());
      return new Result(false);
    }

    String namespace = relatedPipeline.getNamespace();
    String name = relatedPipeline.getName();

    V1alpha1Pipeline pipeline =
        Clients.get(V1alpha1Pipeline.class).lister().namespace(namespace).get(name);

    PipelineInfo pipelineInfo = new PipelineInfo();
    PipelineInfoSpec infoSpec = new PipelineInfoSpec();
    pipelineInfo.setSpec(infoSpec);

    logger.debug(
        "Start to sync Run {} Parameters to Pipeline '{}/{}'",
        run.getFullDisplayName(),
        namespace,
        name);
    List<V1alpha1PipelineParameter> parameters = getParametersFromRun(run, pipeline);
    infoSpec.setParameters(parameters);
    logger.debug(
        "Add {} parameters from Run {} to Pipeline '{}/{}'",
        parameters.size(),
        run.getFullDisplayName(),
        namespace,
        name);

    logger.debug(
        "Start to sync Run {} causes to Pipeline '{}/{}'",
        run.getFullDisplayName(),
        namespace,
        name);
    List<PipelineCause> causes = getCausesFromRun(run);
    infoSpec.setCauses(causes);
    logger.debug(
        "Add {} causes from Run {} to Pipeline '{}/{}'",
        causes.size(),
        run.getFullDisplayName(),
        namespace,
        name);

    logger.debug(
        "Start to sync Run {} downstream to Pipeline '{}/{}'",
        run.getFullDisplayName(),
        namespace,
        name);
    List<PipelineDownstream> downstreams = getDownstreamFromRun(run);
    infoSpec.setDownstreams(downstreams);
    logger.debug(
        "Add {} downstream from Run {} to Pipeline '{}/{}'",
        downstreams.size(),
        run.getFullDisplayName(),
        namespace,
        name);

    PipelineInfoStatus status = new PipelineInfoStatus();
    PipelineStatusJenkins statusJenkins = new PipelineStatusJenkins();
    status.setStatusJenkins(statusJenkins);
    pipelineInfo.setStatus(status);

    RunExt runExt = RunExt.create(run);
    statusJenkins.setStatus(runExt.getStatus().toString());
    if (run.getResult() != null) {
      statusJenkins.setResult(run.getResult().toString());
    }

    statusJenkins.setArtifacts(
        run.getArtifacts()
            .stream()
            .map(artifact -> new PipelineStatusJenkinsArtifact(artifact.getFileName()))
            .collect(Collectors.toList()));
    statusJenkins.setBuild(run.getId());
    statusJenkins.setChanges(
        run.getChangeSets()
            .stream()
            .flatMap(
                entries -> Arrays.stream(entries.getItems()).map(item -> (ChangeLogSet.Entry) item))
            .map(
                entry ->
                    new PipelineSCMChange(
                        entry.getCommitId(), entry.getMsg(), entry.getAuthor().getFullName()))
            .collect(Collectors.toList()));

    List<PipelineSCMInfo> scmInfos = getPipelineSCMInfosFromRun(run);
    statusJenkins.setScmInfos(scmInfos);

    TimeInQueueAction timeInQueueAction = run.getAction(TimeInQueueAction.class);
    if (timeInQueueAction != null) {
      long waitingDurationInMillis = timeInQueueAction.getWaitingTimeMillis();
      statusJenkins.setWaitingDurationInMillis(waitingDurationInMillis);

      long buildingDurationInMillis = timeInQueueAction.getBuildingDurationMillis();
      statusJenkins.setBuildingDurationInMillis(buildingDurationInMillis);
    }

    Gson gson = new Gson();
    String pipelineInfoString = gson.toJson(pipelineInfo);
    V1alpha1Pipeline pipelineCopy = DeepCopyUtils.deepCopy(pipeline);
    pipeline
        .getMetadata()
        .putAnnotationsItem(EXPERIMENTAL_FEATURE_PIPELINE_INFO, pipelineInfoString);

    PipelineClient pipelineClient = (PipelineClient) Clients.get(V1alpha1Pipeline.class);
    pipelineClient.update(pipelineCopy, pipeline);

    return new Result(false);
  }

  private List<PipelineSCMInfo> getPipelineSCMInfosFromRun(WorkflowRun run) {
    WorkflowJob parentJob = run.getParent();
    if (parentJob.getParent() instanceof WorkflowMultiBranchProject) {
      PipelineSCMInfo scmInfo = new PipelineSCMInfo();

      PullRequest pr = PipelineGenerator.getPR(parentJob);
      if (pr != null) {
        PipelineSCMInfoPR prInfo = new PipelineSCMInfoPR();
        prInfo.setId(pr.getId());
        prInfo.setSource(pr.getSourceBranch());
        prInfo.setTarget(pr.getTargetBranch());
        prInfo.setTitle(pr.getTitle());
        prInfo.setUrl(pr.getUrl());
        scmInfo.setPrInfo(prInfo);
      } else {
        PipelineSCMInfoBranch branch = new PipelineSCMInfoBranch();
        BranchJobProperty property = parentJob.getProperty(BranchJobProperty.class);
        if (property != null) {
          branch.setBranch(property.getBranch().getName());
        }
        scmInfo.setBranchInfo(branch);
      }
      return Collections.singletonList(scmInfo);
    } else {
      List<PipelineSCMInfo> scmInfos = new LinkedList<>();
      // If it is not a multi branch job, we will try to get scm info from its actions
      List<BuildData> BuildDataList = run.getActions(BuildData.class);
      for (BuildData buildData : BuildDataList) {
        PipelineSCMInfo scmInfo = new PipelineSCMInfo();
        PipelineSCMInfoBranch branch = new PipelineSCMInfoBranch();
        scmInfo.setBranchInfo(branch);

        Revision revision = buildData.getLastBuiltRevision();
        if (revision == null) {
          continue;
        }

        if (revision.getBranches() == null || revision.getBranches().size() == 0) {
          continue;
        }

        // we only use the first branch name
        branch.setBranch(revision.getBranches().iterator().next().getName());

        scmInfos.add(scmInfo);
      }
      return scmInfos;
    }
  }

  private List<V1alpha1PipelineParameter> getParametersFromRun(
      WorkflowRun run, V1alpha1Pipeline pipeline) {
    // TODO for convenient, we use parameters from spec directly, should refactor it in the future
    return pipeline.getSpec().getParameters();
  }

  private List<PipelineCause> getCausesFromRun(WorkflowRun run) {
    List<Cause> causes = run.getCauses();
    // convert causes to PipelineCauses
    return causes
        .stream()
        .flatMap(rawJenkinsCause -> convertToCauses(rawJenkinsCause).stream())
        .collect(Collectors.toList());
  }

  private List<PipelineDownstream> getDownstreamFromRun(WorkflowRun run) {
    // TODO
    return Collections.emptyList();
  }

  @Nonnull
  private List<PipelineCause> convertToCauses(Cause rawJenkinsCause) {
    if (rawJenkinsCause instanceof UpstreamCause) {
      return convertUpstreamCauses(((UpstreamCause) rawJenkinsCause));
    } else if (rawJenkinsCause instanceof JenkinsPipelineCause) {
      return convertJenkinsPipelineCause(((JenkinsPipelineCause) rawJenkinsCause));
    } else if (rawJenkinsCause instanceof ReplayCause) {
      return convertReplayCause(((ReplayCause) rawJenkinsCause));
    }
    return Collections.emptyList();
  }

  private List<PipelineCause> convertUpstreamCauses(UpstreamCause upstreamCause) {
    // for now, we will convert all its transitive upstream causes and not keep their hierarchical
    // structure.
    if (CollectionUtils.isEmpty(upstreamCause.getUpstreamCauses())
        || upstreamCause.getUpstreamCauses().stream().noneMatch(c -> c instanceof UpstreamCause)) {
      return Collections.singletonList(convertUpstreamCause(upstreamCause));
    }

    List<PipelineCause> pipelineCauses = new LinkedList<>();
    for (Cause cause : upstreamCause.getUpstreamCauses()) {
      if (cause instanceof UpstreamCause) {
        pipelineCauses.addAll(convertUpstreamCauses(((UpstreamCause) cause)));
      }
    }

    return pipelineCauses;
  }

  @Nonnull
  private PipelineCause convertUpstreamCause(@Nonnull UpstreamCause upstreamCauseWithoutParent) {
    PipelineCause pipelineCause = new PipelineCause();
    pipelineCause.setType(PipelineCauseType.UPSTREAM);
    pipelineCause.setDescription(upstreamCauseWithoutParent.getShortDescription());

    pipelineCause.setProperty(
        PipelineCauseUpstreamProperty.BUILD, upstreamCauseWithoutParent.getUpstreamBuild() + "");
    pipelineCause.setProperty(
        PipelineCauseUpstreamProperty.PROJECT, upstreamCauseWithoutParent.getUpstreamProject());
    pipelineCause.setProperty(
        PipelineCauseUpstreamProperty.URL, upstreamCauseWithoutParent.getUpstreamUrl());

    return pipelineCause;
  }

  @Nonnull
  private List<PipelineCause> convertJenkinsPipelineCause(JenkinsPipelineCause cause) {
    PipelineCause pipelineCause = new PipelineCause();

    pipelineCause.setType(PipelineCauseType.PIPELINE);
    pipelineCause.setProperty("namespace", cause.getNamespace());
    pipelineCause.setProperty("name", cause.getName());

    return Collections.singletonList(pipelineCause);
  }

  private List<PipelineCause> convertReplayCause(ReplayCause replayCause) {
    PipelineCause pipelineCause = new PipelineCause();

    pipelineCause.setType(PipelineCauseType.REPLAY);
    pipelineCause.setDescription(replayCause.getShortDescription());
    pipelineCause.setProperty(
        PipelineCauseReplayProperty.ORIGIN_NUM, replayCause.getOriginalNumber() + "");

    return Collections.singletonList(pipelineCause);
  }

  private static ThreadFactory namedRunSyncWorkerThreadFactory() {
    return new ThreadFactoryBuilder().setNameFormat("PipelineSyncWorker" + "-%d").build();
  }
}
