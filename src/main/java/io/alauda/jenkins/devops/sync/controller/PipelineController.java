package io.alauda.jenkins.devops.sync.controller;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_LABELS_REPLAYED_FROM;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.CANCELLED;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.FAILED;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.QUEUED;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.client.PipelineClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.controller.predicates.BindResourcePredicate;
import io.alauda.jenkins.devops.sync.monitor.Metrics;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.ReplayUtils;
import io.kubernetes.client.ApiException;
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
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.annotation.Nonnull;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
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
                      null,
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
                              if (oldPipeline
                                  .getMetadata()
                                  .getResourceVersion()
                                  .equals(newPipeline.getMetadata().getResourceVersion())) {
                                logger.debug(
                                    "[{}] resourceVersion of Pipeline '{}/{}' is equal, will skip update event for it",
                                    CONTROLLER_NAME,
                                    namespace,
                                    name);
                                return false;
                              }

                              lastEventComingTime = LocalDateTime.now();

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
            .withReadyFunc(Clients::allRegisteredResourcesSynced)
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

    if (pipelineList == null
        || pipelineList.getItems() == null
        || pipelineList.getItems().size() == 0) {
      return false;
    }

    return true;
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

      if (!BindResourcePredicate.isBindedResource(
          namespace, pipeline.getSpec().getJenkinsBinding().getName())) {
        logger.debug(
            "[{}] Pipeline '{}/{}' is not bind to correct jenkinsbinding, will skip it",
            getControllerName(),
            namespace,
            name);
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
        if (isNewPipeline(pipelineCopy)) {
          logger.debug(
              "[{}] Pipeline '{}/{} phase is {}, will trigger a new build",
              getControllerName(),
              namespace,
              name,
              pipeline.getStatus().getPhase());

          if (isCreateByJenkins(pipelineCopy)) {
            logger.debug(
                "[{}] Pipeline created by Jenkins. It should be triggered, skip create event.",
                getControllerName());
            pipelineCopy.getStatus().setPhase(QUEUED);
            boolean succeed = pipelineClient.update(pipeline, pipelineCopy);
            return new Result(!succeed);
          }

          if (AlaudaUtils.isCancellable(pipelineCopy.getStatus())
              && AlaudaUtils.isCancelled(pipelineCopy.getStatus())) {
            pipelineCopy.getStatus().setPhase(CANCELLED);
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

              logger.info("[{}] Pipeline '{}/{}' Replayed from Pipeline '{}/{}'", getControllerName(),
                      namespace, name, namespace, originalName);

              // 放到到 JenkinsUtils 里
              ReplayUtils.replayJob(
                  job, pipelineConfig.getMetadata().getUid(), pipelineCopy, originalPipeline);
            } else {
              JenkinsUtils.triggerJob(job, pipelineCopy);
            }

            logger.info("[{}] Successfully triggered Pipeline '{}/{}'", getControllerName(),
                namespace, name);
            pipelineCopy.getStatus().setPhase(QUEUED);
          } catch (Exception e) {
            logger.info(
                "[{}] Unable to trigger Pipeline '{}/{}', reason: {}",
                getControllerName(),
                namespace,
                name,
                e.getMessage());

            pipelineCopy.getStatus().setPhase(FAILED);
          }

          pipelineClient.update(pipeline, pipelineCopy);
          return new Result(false);
        }

        if (AlaudaUtils.isCancellable(pipelineCopy.getStatus())
            && AlaudaUtils.isCancelled(pipeline.getStatus())) {
          logger.debug(
              "[{}] Starting cancel Pipeline '{}/{}'", getControllerName(), namespace, name);
          boolean succeed = jenkinsClient.cancelPipeline(new NamespaceName(namespace, name));
          if (succeed) {
            pipelineCopy.getStatus().setPhase(CANCELLED);
            succeed = pipelineClient.update(pipeline, pipelineCopy);
            return new Result(!succeed);
          } else {
            pipelineClient.update(pipeline, pipelineCopy);
            return new Result(true);
          }
        }

        return new Result(false);
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

    private String getControllerName() {
      return CONTROLLER_NAME;
    }

    private boolean isNewPipeline(@Nonnull V1alpha1Pipeline pipeline) {
      return pipeline.getStatus().getPhase().equals(PipelinePhases.PENDING);
    }

    private boolean isCreateByJenkins(@Nonnull V1alpha1Pipeline pipeline) {
      Map<String, String> labels = pipeline.getMetadata().getLabels();
      return (labels != null
          && Constants.ALAUDA_SYNC_PLUGIN.equals(labels.get(Constants.PIPELINE_CREATED_BY)));
    }
  }
}
