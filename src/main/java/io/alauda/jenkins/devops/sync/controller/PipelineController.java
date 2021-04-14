package io.alauda.jenkins.devops.sync.controller;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import hudson.Extension;
import hudson.model.Queue;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.client.PipelineClient;
import io.alauda.jenkins.devops.sync.exception.PipelineException;
import io.alauda.jenkins.devops.sync.listener.PipelineSyncExecutor;
import io.alauda.jenkins.devops.sync.monitor.Metrics;
import io.alauda.jenkins.devops.sync.tasks.period.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.util.ConditionUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.ReplayUtils;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.workqueue.DefaultRateLimitingQueue;
import io.kubernetes.client.extended.workqueue.RateLimitingQueue;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiException;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineController
    implements ResourceController, ConnectionAliveDetectTask.HeartbeatResourceDetector {

  private static final Logger logger = LoggerFactory.getLogger(PipelineController.class);
  private static final String CONTROLLER_NAME = "PipelineController";

  private RateLimitingQueue<Request> queue;
  private LocalDateTime lastEventComingTime;

  @Override
  public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    SharedIndexInformer<V1alpha1Pipeline> informer =
        factory.getExistingSharedIndexInformer(V1alpha1Pipeline.class);
    if (informer == null) {
      informer =
          factory.sharedIndexInformerFor(
              callGeneratorParams ->
                  api.listPipelineForAllNamespacesCall(
                      null,
                      null,
                      null,
                      "jenkins=" + AlaudaSyncGlobalConfiguration.get().getJenkinsService(),
                      null,
                      null,
                      callGeneratorParams.resourceVersion,
                      callGeneratorParams.timeoutSeconds,
                      callGeneratorParams.watch,
                      null),
              V1alpha1Pipeline.class,
              V1alpha1PipelineList.class,
              TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
    }

    PipelineClient client = new PipelineClient(informer);
    Clients.register(V1alpha1Pipeline.class, client);

    queue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadExecutor());

    Controller controller =
        ControllerBuilder.defaultBuilder(factory)
            .withWorkQueue(queue)
            .watch(
                (workQueue) ->
                    ControllerBuilder.controllerWatchBuilder(V1alpha1Pipeline.class, workQueue)
                        .withWorkQueueKeyFunc(
                            pipeline ->
                                new Request(
                                    pipeline.getMetadata().getNamespace(),
                                    pipeline.getMetadata().getName()))
                        .withOnAddFilter(
                            pipeline -> {
                              Metrics.incomingRequestCounter.labels("pipeline", "add").inc();
                              logger.debug(
                                  "[{}] received event: Add, Pipeline '{}/{}'",
                                  CONTROLLER_NAME,
                                  pipeline.getMetadata().getNamespace(),
                                  pipeline.getMetadata().getName());
                              return true;
                            })
                        .withOnUpdateFilter(
                            (oldPipeline, newPipeline) -> {
                              Metrics.incomingRequestCounter.labels("pipeline", "update").inc();
                              String namespace = oldPipeline.getMetadata().getNamespace();
                              String name = oldPipeline.getMetadata().getName();

                              logger.debug(
                                  "[{}] received event: Update, Pipeline '{}/{}'",
                                  CONTROLLER_NAME,
                                  namespace,
                                  name);
                              return true;
                            })
                        .withOnDeleteFilter(
                            (pipeline, aBoolean) -> {
                              Metrics.incomingRequestCounter.labels("pipeline", "delete").inc();
                              logger.debug(
                                  "[{}] received event: Delete, Pipeline '{}/{}'",
                                  CONTROLLER_NAME,
                                  pipeline.getMetadata().getNamespace(),
                                  pipeline.getMetadata().getName());
                              return true;
                            })
                        .build())
            .withReconciler(new PipelineReconciler(new Lister<>(informer.getIndexer())))
            .withName(CONTROLLER_NAME)
            .withWorkerCount(4)
            .build();

    managerBuilder.addController(controller);
  }

  @Override
  public LocalDateTime lastEventComingTime() {
    return lastEventComingTime;
  }

  @Override
  public String resourceName() {
    return "Pipeline";
  }

  @Override
  public boolean hasResourceExists() throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    V1alpha1PipelineList pipelineList =
        api.listPipelineForAllNamespaces(null, null, null, null, 1, null, "0", null, null);

    return pipelineList != null
        && pipelineList.getItems() != null
        && pipelineList.getItems().size() != 0;
  }

  class PipelineReconciler implements Reconciler {

    private Lister<V1alpha1Pipeline> lister;
    private JenkinsClient jenkinsClient;

    public PipelineReconciler(Lister<V1alpha1Pipeline> lister) {
      this.lister = lister;
      jenkinsClient = JenkinsClient.getInstance();
    }

    @Override
    public Result reconcile(Request request) {
      lastEventComingTime = LocalDateTime.now();

      Metrics.completedRequestCounter.labels("pipeline").inc();
      Metrics.remainedRequestsGauge.labels("pipeline").set(queue.length());

      String namespace = request.getNamespace();
      String name = request.getName();

      V1alpha1Pipeline pipeline = lister.namespace(namespace).get(name);
      if (pipeline == null) {
        logger.debug(
            "[{}] Cannot found Pipeline '{}/{}' in local lister, will try to remove it's correspondent Jenkins build",
            getControllerName(),
            namespace,
            name);

        boolean deleteSucceed;
        try {
          deleteSucceed = jenkinsClient.deletePipeline(new NamespaceName(namespace, name));
          if (!deleteSucceed) {
            logger.warn(
                "[{}] Failed to delete build for Pipeline '{}/{}'",
                getControllerName(),
                namespace,
                name);
          }
        } catch (Exception e) {
          logger.warn(
              "[{}] Failed to delete build for Pipeline '{}/{}', reason {}",
              getControllerName(),
              namespace,
              name,
              e.getMessage());
        }
        return new Result(false);
      }

      V1alpha1PipelineConfig pipelineConfig =
          Clients.get(V1alpha1PipelineConfig.class)
              .lister()
              .namespace(namespace)
              .get(pipeline.getSpec().getPipelineConfig().getName());
      if (pipelineConfig == null) {
        logger.error(
            "[{}] Unable to find PipelineConfig for Pipeline '{}/{}'",
            getControllerName(),
            namespace,
            name);
        return new Result(true);
      }

      synchronized (pipeline.getMetadata().getUid().intern()) {
        PipelineClient pipelineClient = (PipelineClient) Clients.get(V1alpha1Pipeline.class);
        V1alpha1Pipeline pipelineCopy = DeepCopyUtils.deepCopy(pipeline);
        // ensure we won't update Pipeline's spec
        pipelineCopy.setSpec(pipeline.getSpec());

        V1alpha1Condition syncedCondition =
            ConditionUtils.getCondition(
                pipelineCopy.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_SYNCED);
        V1alpha1Condition completedCondition =
            ConditionUtils.getCondition(
                pipelineCopy.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_COMPLETED);
        V1alpha1Condition cancelledCondition =
            ConditionUtils.getCondition(
                pipelineCopy.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_CANCELLED);
        if (syncedCondition == null || cancelledCondition == null || completedCondition == null) {
          logger.debug(
              "[{}] Pipeline '{}/{}' doesn't have synced or cancelled condition, will skip this reconcile",
              getControllerName(),
              namespace,
              name);
          return new Result(false);
        }

        if (completedCondition.getStatus().equals(CONDITION_STATUS_TRUE)) {
          logger.debug(
              "[{}] Pipeline '{}/{}' is completed, will skip this reconcile",
              getControllerName(),
              namespace,
              name);
          return new Result(false);
        }

        if (syncedCondition.getStatus().equals(CONDITION_STATUS_UNKNOWN)) {
          syncedCondition.status(CONDITION_STATUS_TRUE).lastAttempt(DateTime.now());
          logger.debug(
              "[{}] Pipeline '{}/{} synced condition is Unknown, will trigger a new build",
              getControllerName(),
              namespace,
              name);
          if (isCreateByJenkins(pipelineCopy)) {
            logger.debug(
                "[{}] Pipeline created by Jenkins. It should be triggered, skip create event.",
                getControllerName());
            syncedCondition.setStatus(CONDITION_STATUS_TRUE);
            boolean succeed = pipelineClient.update(pipeline, pipelineCopy);
            return new Result(!succeed);
          }

          if (isCancelling(pipeline)) {
            cancelledCondition.setLastAttempt(DateTime.now());
            cancelledCondition.setStatus(CONDITION_STATUS_TRUE);
            boolean succeed = pipelineClient.update(pipeline, pipelineCopy);
            return new Result(!succeed);
          }

          WorkflowJob job = jenkinsClient.getJob(pipelineCopy, pipelineConfig);
          if (job == null) {
            logger.error(
                "[{}] Unable to find Jenkins job for PipelineConfig '{}/{}'",
                getControllerName(),
                namespace,
                pipelineConfig.getMetadata().getName());
            return new Result(true);
          }

          try {
            if (isRelayed(pipelineCopy)) {
              String originalName =
                  pipelineCopy.getMetadata().getLabels().get(PIPELINE_LABELS_REPLAYED_FROM);
              V1alpha1Pipeline originalPipeline = lister.namespace(namespace).get(originalName);

              logger.info(
                  "[{}] Pipeline '{}/{}' Replayed from Pipeline '{}/{}'",
                  getControllerName(),
                  namespace,
                  name,
                  namespace,
                  originalName);

              // 放到到 JenkinsUtils 里
              ReplayUtils.replayJob(
                  job, pipelineConfig.getMetadata().getUid(), pipelineCopy, originalPipeline);
            } else {
              JenkinsUtils.triggerJob(job, pipelineCopy);
            }

            logger.info(
                "[{}] Successfully triggered Pipeline '{}/{}'",
                getControllerName(),
                namespace,
                name);
            syncedCondition.setStatus(CONDITION_STATUS_TRUE);
          } catch (Exception e) {
            logger.info(
                "[{}] Unable to trigger Pipeline '{}/{}', reason: {}",
                getControllerName(),
                namespace,
                name,
                e.getMessage());

            syncedCondition.setStatus(CONDITION_STATUS_FALSE);
            syncedCondition.setReason(PIPELINE_CONDITION_REASON_TRIGGER_FAILED);
            syncedCondition.setMessage(e.getMessage());
          }

          pipelineClient.update(pipeline, pipelineCopy);
          return new Result(false);
        }

        if (isCancelling(pipeline)) {
          cancelledCondition.setLastAttempt(DateTime.now());
          logger.debug(
              "[{}] Starting cancel Pipeline '{}/{}'", getControllerName(), namespace, name);
          PipelineException cancelException = null;
          try {
            jenkinsClient.cancelPipeline(new NamespaceName(namespace, name));
            logger.debug(
                "[{}] Succeed to cancel Pipeline '{}/{}'", getControllerName(), namespace, name);
          } catch (PipelineException e) {
            cancelException = e;
            logger.error(
                "[{}] Failed to cancel Pipeline '{}/{}, reason {}",
                getControllerName(),
                namespace,
                name,
                e);
          }

          failItIfCancelFail(
              pipeline, pipelineCopy, pipelineConfig, pipelineClient, cancelException);
          return new Result(false);
        }

        if (completedCondition.getStatus().equals(CONDITION_STATUS_FALSE)
            || completedCondition.getStatus().equals(CONDITION_STATUS_UNKNOWN)) {
          WorkflowJob job = jenkinsClient.getJob(pipeline, pipelineConfig);
          if (job == null) {
            logger.info(
                "Failed to add pipeline '{}/{}' to poll queue, unable to find related job",
                namespace,
                name);
            return new Result(false);
          }

          boolean hasInQueue = hasBuildInQueueOf(job);
          WorkflowRun run = JenkinsUtils.getRun(job, pipeline);
          if (run == null) {
            logger.info(
                "Failed to add pipeline '{}/{}' to poll queue, unable to find related run",
                namespace,
                name);

            if (!hasInQueue) {
              failItByQueueLost(pipeline, pipelineCopy, pipelineClient, job, completedCondition);
            }

            return new Result(false);
          }

          if (!run.isBuilding()) {
            PipelineSyncExecutor.getInstance().submit(run);
          }
        }

        return new Result(false);
      }
    }

    private void failItIfCancelFail(
        V1alpha1Pipeline pipeline,
        V1alpha1Pipeline pipelineCopy,
        V1alpha1PipelineConfig pipelineConfig,
        PipelineClient pipelineClient,
        PipelineException e) {

      V1alpha1Condition cancelledCondition =
          ConditionUtils.getCondition(
              pipelineCopy.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_CANCELLED);

      if (e != null) {
        cancelledCondition.setStatus(CONDITION_STATUS_FALSE);
        cancelledCondition.setReason(PIPELINE_CONDITION_REASON_CANCELLING_FAILED);
        cancelledCondition.setMessage(e.getMessage());

        logger.info(String.format("updating pipeline to failure, "));
        pipelineClient.update(pipeline, pipelineCopy);
        return;
      }

      // e is null, check run status, if it is in building , and more time than 2 min, will just
      // hack to abort it
      WorkflowJob job;
      try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
        job = jenkinsClient.getJob(pipeline, pipelineConfig);
      }

      if (job == null) {
        cancelledCondition.setStatus(CONDITION_STATUS_FALSE);
        cancelledCondition.setReason(PIPELINE_CONDITION_REASON_CANCELLING_FAILED);
        cancelledCondition.setMessage("cannot find correspondent workflow job");

        pipelineClient.update(pipeline, pipelineCopy);
        return;
      }

      WorkflowRun run = JenkinsUtils.getRun(job, pipeline);
      if (run == null) {
        cancelledCondition.setStatus(CONDITION_STATUS_FALSE);
        cancelledCondition.setReason(PIPELINE_CONDITION_REASON_CANCELLING_FAILED);
        cancelledCondition.setMessage("cannot find correspondent workflow run");

        pipelineClient.update(pipeline, pipelineCopy);
        return;
      }

      if (!run.isBuilding()) {
        cancelledCondition.setStatus(CONDITION_STATUS_TRUE);
        logger.debug(
            String.format(
                "we are ensure the build '%s' is stopped, result: %s, pipeline: '%s'",
                run.getFullDisplayName(), run.getResult(), pipeline.getMetadata().getName()));
        pipelineClient.update(pipeline, pipelineCopy);
        return;
      }

      // run is still in building
      logger.debug(
          String.format(
              "jenkins build: '%s' is canceled success, but the build is still in building, pipeline: %s",
              run.getFullDisplayName(), pipeline.getMetadata().getName()));
      Map<String, String> annotations = pipelineCopy.getMetadata().getAnnotations();
      String countS = annotations.getOrDefault(ANNOTATION_PIPELINE_CANCEL_RETRY.get(), "0");
      Integer count = 0;
      try {
        count = Integer.parseInt(countS);
      } catch (NumberFormatException ex) {
        logger.debug("cannot parse cancel retry count to int, just use 0");
        count = 0;
      }

      int maxRetryCount = 5;
      if (count <= maxRetryCount) {
        count = count + 1;
        annotations.put(ANNOTATION_PIPELINE_CANCEL_RETRY.get().toString(), count.toString());
        pipelineClient.update(pipeline, pipelineCopy);
        return;
      }

      // got max retry count, we did not cancel it success, just set result as aborted (⊙︿⊙)
      logger.info(
          String.format(
              "hack to cancel jenkins build: '%s', pipeline: %s",
              run.getFullDisplayName(), pipeline.getMetadata().getName()));
      run.setResult(hudson.model.Result.ABORTED);
      cancelledCondition.setStatus(CONDITION_STATUS_TRUE);
      cancelledCondition.setReason(PIPELINE_CONDITION_REASON_CANCELLED);
      cancelledCondition.setMessage("already hack to cancel jenkins build");
      pipelineClient.update(pipeline, pipelineCopy);
    }

    private boolean failItByQueueLost(
        V1alpha1Pipeline old,
        V1alpha1Pipeline pipelineCopy,
        PipelineClient client,
        WorkflowJob job,
        V1alpha1Condition completedCondition) {

      V1alpha1Condition syncedCondition =
          ConditionUtils.getCondition(
              pipelineCopy.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_SYNCED);
      long diff = DateTime.now().getMillis() - syncedCondition.getLastAttempt().getMillis();

      if (diff / 1000 < 10 * 60) { // wait 10 min
        // jenkins trigger build is async, when build is triggered barely, we are not ensure the
        // status of jenkins queue.
        // so , just return false and wait for next syncing loop
        return false;
      }

      logger.info(
          String.format(
              "set pipeline to failure, because no build in queue of job '%s' , "
                  + "but the pipeline '%s' is still in phase '%s'",
              job.getFullName(),
              pipelineCopy.getMetadata().getName(),
              pipelineCopy.getStatus().getPhase()));
      completedCondition.setStatus(CONDITION_STATUS_TRUE);
      completedCondition.setReason(PIPELINE_CONDITION_REASON_CANCELLED);
      completedCondition.setMessage(
          "There is no queue and run in jenkins, may be jenkins chuck the queue data during restart");
      return client.update(old, pipelineCopy);
    }

    private boolean hasBuildInQueueOf(WorkflowJob job) {
      boolean hasUnknownType = false;

      try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {

        Queue queue = Jenkins.get().getQueue();
        Queue.Item[] items = queue.getItems();

        for (Queue.Item item : items) {

          if (WorkflowJob.class.isInstance(item.task.getOwnerTask())) {
            WorkflowJob jobQ = (WorkflowJob) item.task.getOwnerTask();
            if (jobQ.getFullName().equals(job.getFullName())) {
              return true;
            }
          } else {
            logger.debug(
                "found unknown task in queue: task: %s, ownertask: %s",
                item.task, item.task.getOwnerTask());
            hasUnknownType = true;
          }
        }

        if (hasUnknownType) {
          return !queue.isEmpty();
        }

        logger.debug(
            String.format(
                "not found any build in queue of this job %s,queue len: %d",
                job.getFullName(), items.length));
        return false;
      }
    }

    /**
     * Check if the pipeline was replayed from another one
     *
     * @param pipeline the pipeline object
     * @return true, if the pipeline was replayed from another one
     */
    private boolean isRelayed(V1alpha1Pipeline pipeline) {
      Map<String, String> labels = pipeline.getMetadata().getLabels();
      if (labels != null) {
        String replayedFrom = labels.get(PIPELINE_LABELS_REPLAYED_FROM);
        return StringUtils.isNotBlank(replayedFrom);
      }
      return false;
    }

    private boolean isCancelling(V1alpha1Pipeline pipeline) {
      if (!pipeline.getSpec().getCancel()) {
        return false;
      }

      V1alpha1Condition cancelledCond =
          ConditionUtils.getCondition(
              pipeline.getStatus().getConditions(), PIPELINE_CONDITION_TYPE_CANCELLED);
      return cancelledCond != null && cancelledCond.getStatus().equals(CONDITION_STATUS_UNKNOWN);
    }

    private String getControllerName() {
      return CONTROLLER_NAME;
    }

    private boolean isCreateByJenkins(@Nonnull V1alpha1Pipeline pipeline) {
      Map<String, String> labels = pipeline.getMetadata().getLabels();
      return (labels != null && ALAUDA_SYNC_PLUGIN.equals(labels.get(PIPELINE_CREATED_BY)));
    }
  }
}
