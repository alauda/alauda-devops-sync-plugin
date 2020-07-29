package io.alauda.jenkins.devops.sync.listener;

import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_BRANCH;
import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_PR;
import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_STALE_BRANCH;
import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_STALE_PR;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Job;
import hudson.model.Run;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import io.alauda.jenkins.devops.sync.multiBranch.PullRequest;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineGenerator;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.JSON;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.extended.workqueue.ratelimiter.BucketRateLimiter;
import io.kubernetes.client.models.V1ObjectMeta;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class MultibranchProjectSyncExecutor implements Runnable {

  private static final Logger logger =
      LoggerFactory.getLogger(MultibranchProjectSyncExecutor.class);

  private static final int DEFAULT_WORKER_COUNT = 2;
  private static final MultibranchProjectSyncExecutor instance =
      new MultibranchProjectSyncExecutor();

  public static MultibranchProjectSyncExecutor getInstance() {
    return instance;
  }

  @Initializer(after = InitMilestone.PLUGINS_STARTED)
  @SuppressWarnings("unused")
  @Restricted(DoNotUse.class)
  public static void initializeExecutor() {
    logger.info("Initializing MultibranchProjectSyncExecutor...");

    MultibranchProjectSyncExecutor executor = getInstance();
    ExecutorService threadPool = Executors.newSingleThreadExecutor();
    threadPool.submit(executor);

    logger.info("MultibranchProjectSyncExecutor initialized :P");
  }

  private ScheduledExecutorService executor;
  private RateLimitingQueue<NamespaceName> projectQueue;
  private JenkinsClient jenkinsClient;

  private MultibranchProjectSyncExecutor() {
    executor =
        Executors.newScheduledThreadPool(
            DEFAULT_WORKER_COUNT, namedProjectSyncWorkerThreadFactory());

    projectQueue =
        new DefaultRateLimitingQueue<>(
            Executors.newSingleThreadExecutor(),
            new BucketRateLimiter<>(100, 2, Duration.ofSeconds(1)));
    jenkinsClient = JenkinsClient.getInstance();
  }

  public void submit(NamespaceName multiBranchProject) {
    projectQueue.addRateLimited(multiBranchProject);
  }

  public void run() {
    logger.info(
        "Initializing MultibranchProjectSyncExecutor {} workers, worker count {}",
        this,
        DEFAULT_WORKER_COUNT);
    for (int i = 0; i < DEFAULT_WORKER_COUNT; i++) {
      int finalIndex = i;
      executor.scheduleWithFixedDelay(
          () -> {
            logger.debug("Starting MultibranchProjectSyncExecutor {}", finalIndex);
            worker();
            logger.debug("Resuming MultibranchProjectSyncExecutor {}", finalIndex);
          },
          0,
          1,
          TimeUnit.SECONDS);
    }
  }

  private void worker() {
    while (!projectQueue.isShuttingDown()) {
      NamespaceName namespaceName = null;
      try {
        namespaceName = projectQueue.get();
      } catch (InterruptedException e) {
        logger.error("MultibranchProjectSyncExecutor worker interrupted.", e);
        Thread.currentThread().interrupt();
      }

      if (namespaceName == null) {
        logger.info(
            "MultibranchProjectSyncExecutor worker exiting because work queue has shutdown..");
        return;
      }

      try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
        updateBranchAndPRAnnotations(namespaceName);
      } catch (Throwable e) {
        logger.error(
            "Failed to sync MultibranchProject '{}/{}' to PipelineConfig, reason {}",
            namespaceName.getNamespace(),
            namespaceName.getName(),
            e);
      } finally {
        projectQueue.done(namespaceName);
      }
    }
  }

  private void updateBranchAndPRAnnotations(NamespaceName namespaceName) {
    String namespace = namespaceName.getNamespace();
    String name = namespaceName.getName();

    WorkflowMultiBranchProject project = jenkinsClient.getMultiBranchProject(namespaceName);
    if (project == null) {
      logger.info(
          "Failed to sync MultibranchProject '{}/{}' to PipelineConfig, reason unable to find project in Jenkins",
          namespace,
          name);
      return;
    }

    V1alpha1PipelineConfig oldPC;
    try {
      oldPC = readPipelineConfig(namespace, name);
    } catch (ApiException e) {
      logger.info(
          "Failed to sync MultibranchProject '{}/{}' to PipelineConfig, reason unable to find PipelineConfig",
          namespaceName.getNamespace(),
          namespaceName.getName());
      return;
    }
    if (oldPC == null) {
      logger.info(
          "Failed to sync MultibranchProject '{}/{}' to PipelineConfig, reason unable to find PipelineConfig",
          namespaceName.getNamespace(),
          namespaceName.getName());
      return;
    }

    V1alpha1PipelineConfig newPC = DeepCopyUtils.deepCopy(oldPC);
    V1ObjectMeta meta = newPC.getMetadata();

    // clean up all annotations which start with alauda.io/jenkins
    clearBranchAndPRAnnotations(meta.getAnnotations());

    BranchItem branchItem = new BranchItem();
    Collection<? extends Job> allJobs = project.getAllJobs();

    logger.info("project {}", project.getFullDisplayName());

    logger.info("Jon number {}", allJobs.size());

    // sort the jobs by the latest build start time
    allJobs =
        allJobs
            .stream()
            .sorted(
                (jobLeft, jobRight) -> {
                  Run leftNewestBuild = jobLeft.getLastBuild();
                  Run rightNewestBuild = jobRight.getLastBuild();

                  if (leftNewestBuild == null) {
                    return 1;
                  }
                  if (rightNewestBuild == null) {
                    return -1;
                  }

                  return -Long.compare(
                      leftNewestBuild.getStartTimeInMillis(),
                      rightNewestBuild.getStartTimeInMillis());
                })
            .collect(Collectors.toList());

    for (Job job : allJobs) {
      if (!(job instanceof WorkflowJob)) {
        continue;
      }

      WorkflowJob wfJob = (WorkflowJob) job;
      BranchJobProperty pro = wfJob.getProperty(BranchJobProperty.class);
      if (pro == null) {
        continue;
      }

      String scmURL = "";
      ObjectMetadataAction metadataAction = wfJob.getAction(ObjectMetadataAction.class);
      if (metadataAction != null) {
        scmURL = metadataAction.getObjectUrl();
      }

      String branchName = pro.getBranch().getName();
      PullRequest pr = PipelineGenerator.getPR(wfJob);
      if (pr != null) {
        // we consider it as a pr
        pr.setUrl(scmURL);
        putIfNotEmpty(
            meta,
            ResourceControllerManager.getControllerManager()
                    .getFormattedAnnotation("jenkins.")
                    .get()
                + annotationKeySpec(branchName),
            toJSON(pr));
      } else {
        putIfNotEmpty(
            meta,
            ResourceControllerManager.getControllerManager()
                    .getFormattedAnnotation("jenkins.")
                    .get()
                + annotationKeySpec(branchName)
                + ".url",
            scmURL);
      }

      branchItem.add(wfJob, pr != null, branchName);

      List<V1alpha1PipelineParameter> pipelineParameters =
          PipelineConfigToJobMapper.getPipelineParameter(wfJob);
      putIfNotEmpty(
          meta,
          ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.").get()
              + annotationKeySpec(branchName)
              + ".params",
          toJSON(pipelineParameters));
    }
    logger.info("branch items {}", new JSON().serialize(branchItem));

    putIfNotEmpty(meta, MULTI_BRANCH_PR.get().toString(), branchItem.getPrList());
    putIfNotEmpty(meta, MULTI_BRANCH_STALE_PR.get().toString(), branchItem.getStalePRList());
    putIfNotEmpty(meta, MULTI_BRANCH_BRANCH.get().toString(), branchItem.getBranchList());
    putIfNotEmpty(
        meta, MULTI_BRANCH_STALE_BRANCH.get().toString(), branchItem.getStaleBranchList());

    logger.debug("Starting to update PipelineConfig, old {}, \n new {}", oldPC, newPC);
    Clients.get(V1alpha1PipelineConfig.class).update(oldPC, newPC);
  }

  private V1alpha1PipelineConfig readPipelineConfig(String namespace, String name)
      throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    return api.readNamespacedPipelineConfig(name, namespace, null, null, null);
  }

  private static void clearBranchAndPRAnnotations(Map<String, String> annotations) {
    if (annotations == null) {
      return;
    }

    annotations
        .entrySet()
        .removeIf(
            entry ->
                entry
                    .getKey()
                    .startsWith(
                        ResourceControllerManager.getControllerManager()
                            .getFormattedAnnotation("jenkins")
                            .get()));
  }

  private static void putIfNotEmpty(V1ObjectMeta meta, String key, List<?> value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    meta.putAnnotationsItem(key, toJSON(value));
  }

  private static void putIfNotEmpty(V1ObjectMeta meta, String key, String value) {
    if (StringUtils.isNotEmpty(value)) {
      meta.putAnnotationsItem(key, value);
    }
  }

  private static String toJSON(Object obj) {
    String jsonStr = null;
    if (obj instanceof String) {
      jsonStr = obj.toString();
    } else if (obj instanceof List) {
      jsonStr = JSONArray.fromObject(obj).toString();
    } else {
      jsonStr = JSONObject.fromObject(obj).toString();
    }
    return jsonStr;
  }

  private static String annotationKeySpec(String key) {
    if (key == null) {
      return null;
    }

    return key.replaceAll("[^0-9a-zA-Z-]", "-");
  }

  static class BranchItem {

    private List<String> branchList = new ArrayList<>();
    private List<String> staleBranchList = new ArrayList<>();
    private List<String> prList = new ArrayList<>();
    private List<String> stalePRList = new ArrayList<>();

    public void add(WorkflowJob wfJob, boolean isPR, String branchName) {
      if (wfJob.isDisabled() && isPR) {
        stalePRList.add(branchName);
      } else if (wfJob.isDisabled() && !isPR) {
        staleBranchList.add(branchName);
      } else if (!wfJob.isDisabled() && !isPR) {
        branchList.add(branchName);
      } else {
        prList.add(branchName);
      }
    }

    public List<String> getBranchList() {
      return branchList;
    }

    public List<String> getStaleBranchList() {
      return staleBranchList;
    }

    public List<String> getPrList() {
      return prList;
    }

    public List<String> getStalePRList() {
      return stalePRList;
    }
  }

  private static ThreadFactory namedProjectSyncWorkerThreadFactory() {
    return new ThreadFactoryBuilder()
        .setNameFormat("MultibranchProjectSyncExecutor" + "-%d")
        .build();
  }
}
