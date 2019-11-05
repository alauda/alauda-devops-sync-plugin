package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.client.PipelineConfigClient;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.controller.predicates.BindResourcePredicate;
import io.alauda.jenkins.devops.sync.event.PipelineConfigEvents;
import io.alauda.jenkins.devops.sync.exception.ConditionsUtils;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.monitor.Metrics;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
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
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class PipelineConfigController
    implements ResourceController, ConnectionAliveDetectTask.HeartbeatResourceDetector {

  private static final Logger logger = LoggerFactory.getLogger(PipelineConfigController.class);
  private static final String CONTROLLER_NAME = "PipelineConfigController";

  private LocalDateTime lastEventComingTime;
  private RateLimitingQueue<Request> queue;

  @Override
  public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    SharedIndexInformer<V1alpha1PipelineConfig> informer =
        factory.getExistingSharedIndexInformer(V1alpha1PipelineConfig.class);
    if (informer == null) {
      informer =
          factory.sharedIndexInformerFor(
              callGeneratorParams ->
                  api.listPipelineConfigForAllNamespacesCall(
                      null,
                      null,
                      null,
                      null,
                      null,
                      null,
                      callGeneratorParams.resourceVersion,
                      callGeneratorParams.timeoutSeconds,
                      callGeneratorParams.watch,
                      null,
                      null),
              V1alpha1PipelineConfig.class,
              V1alpha1PipelineConfigList.class,
              TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
    }

    PipelineConfigClient client = new PipelineConfigClient(informer);
    Clients.register(V1alpha1PipelineConfig.class, client);

    queue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadExecutor());

    Controller controller =
        ControllerBuilder.defaultBuilder(factory)
            .withWorkQueue(queue)
            .watch(
                (workQueue) ->
                    ControllerBuilder.controllerWatchBuilder(
                            V1alpha1PipelineConfig.class, workQueue)
                        .withWorkQueueKeyFunc(
                            pipelineConfig ->
                                new Request(
                                    pipelineConfig.getMetadata().getNamespace(),
                                    pipelineConfig.getMetadata().getName()))
                        .withOnAddFilter(
                            pipelineConfig -> {
                              Metrics.incomingRequestCounter.labels("pipeline_config", "add").inc();
                              if (pipelineConfig
                                  .getStatus()
                                  .getPhase()
                                  .equals(PipelineConfigPhase.CREATING)) {
                                logger.debug(
                                    "[{}] phase of PipelineConfig '{}/{}' is {}, will skip it",
                                    CONTROLLER_NAME,
                                    pipelineConfig.getMetadata().getNamespace(),
                                    pipelineConfig.getMetadata().getName(),
                                    PipelineConfigPhase.CREATING);
                                return false;
                              }

                              logger.debug(
                                  "[{}] receives event: Add; PipelineConfig '{}/{}'",
                                  CONTROLLER_NAME,
                                  pipelineConfig.getMetadata().getNamespace(),
                                  pipelineConfig.getMetadata().getName());
                              return true;
                            })
                        .withOnUpdateFilter(
                            (oldPipelineConfig, newPipelineConfig) -> {
                              Metrics.incomingRequestCounter
                                  .labels("pipeline_config", "update")
                                  .inc();

                              String namespace = oldPipelineConfig.getMetadata().getNamespace();
                              String name = oldPipelineConfig.getMetadata().getName();
                              if (oldPipelineConfig
                                  .getMetadata()
                                  .getResourceVersion()
                                  .equals(newPipelineConfig.getMetadata().getResourceVersion())) {
                                logger.debug(
                                    "[{}] resourceVersion of PipelineConfig '{}/{}' is equal, will skip update event for it",
                                    CONTROLLER_NAME,
                                    namespace,
                                    name);
                                return false;
                              }

                              lastEventComingTime = LocalDateTime.now();

                              if (newPipelineConfig
                                  .getStatus()
                                  .getPhase()
                                  .equals(PipelineConfigPhase.CREATING)) {
                                logger.debug(
                                    "[{}] phase of PipelineConfig '{}/{}' is {}, will skip it",
                                    CONTROLLER_NAME,
                                    namespace,
                                    name,
                                    PipelineConfigPhase.CREATING);
                                return false;
                              }

                              logger.debug(
                                  "[{}] receives event: Update; PipelineConfig '{}/{}'",
                                  CONTROLLER_NAME,
                                  namespace,
                                  name);

                              return true;
                            })
                        .withOnDeleteFilter(
                            (pipelineConfig, aBoolean) -> {
                              Metrics.incomingRequestCounter
                                  .labels("pipeline_config", "delete")
                                  .inc();
                              logger.debug(
                                  "[{}] receives event: Delete; PipelineConfig '{}/{}'",
                                  CONTROLLER_NAME,
                                  pipelineConfig.getMetadata().getNamespace(),
                                  pipelineConfig.getMetadata().getName());
                              return true;
                            })
                        .build())
            .withReconciler(new PipelineConfigReconciler(new Lister<>(informer.getIndexer())))
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
    return "PipelineConfig";
  }

  @Override
  public boolean hasResourceExists() throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    V1alpha1PipelineConfigList pipelineConfigList =
        api.listPipelineConfigForAllNamespaces(null, null, null, null, 1, null, "0", null, null);

    if (pipelineConfigList == null
        || pipelineConfigList.getItems() == null
        || pipelineConfigList.getItems().size() == 0) {
      return false;
    }

    return true;
  }

  class PipelineConfigReconciler implements Reconciler {

    private Lister<V1alpha1PipelineConfig> lister;
    private JenkinsClient jenkinsClient;

    public PipelineConfigReconciler(Lister<V1alpha1PipelineConfig> lister) {
      this.lister = lister;
      this.jenkinsClient = JenkinsClient.getInstance();
    }

    @Override
    public Result reconcile(Request request) {
      Metrics.completedRequestCounter.labels("pipeline_config").inc();
      Metrics.remainedRequestsGauge.labels("pipeline_config").set(queue.length());

      String namespace = request.getNamespace();
      String name = request.getName();

      V1alpha1PipelineConfig pc = lister.namespace(namespace).get(name);
      if (pc == null) {
        logger.debug(
            "[{}] Cannot found PipelineConfig '{}/{}' in local lister, will try to remove it's correspondent Jenkins job",
            getControllerName(),
            namespace,
            name);
        boolean deleteSucceed;
        try {
          deleteSucceed = jenkinsClient.deleteJob(new NamespaceName(namespace, name));
          if (!deleteSucceed) {
            logger.warn(
                "[{}] Failed to delete job for PipelineConfig '{}/{}'",
                getControllerName(),
                namespace,
                name);
          } else {
            PipelineConfigEvents.newJobDeletedEvent(
                    namespace, name, "no pipeline config found in jenkins local cache")
                .submit();
          }
        } catch (IOException | InterruptedException e) {
          logger.warn(
              "[{}] Failed to delete job for PipelineConfig '{}/{}', reason {}",
              getControllerName(),
              namespace,
              name,
              e.getMessage());
          PipelineConfigEvents.newFailedDeleteJobEvent(
                  namespace, name, String.format("Failed delete job, reason %s", e.getMessage()))
              .submit();
          Thread.currentThread().interrupt();
        }
        return new Result(false);
      }

      if (!BindResourcePredicate.isBindedResource(
          namespace, pc.getSpec().getJenkinsBinding().getName())) {
        logger.debug(
            "[{}] PipelineConfigController: {}/{}' is not bind to correct jenkinsbinding, will skip it",
            getControllerName(),
            namespace,
            name);
        return new Result(false);
      }

      logger.debug(
          "[{}] Start to create or update Jenkins job for PipelineConfig '{}/{}'",
          getControllerName(),
          namespace,
          name);
      V1alpha1PipelineConfig pipelineConfigCopy = DeepCopyUtils.deepCopy(pc);

      boolean alreadyHasJobInJenkins =
          jenkinsClient.getJob(new NamespaceName(namespace, name)) != null;
      synchronized (pc.getMetadata().getUid().intern()) {
        // clean conditions first, any error info will be put it into conditions
        List<V1alpha1Condition> conditions = new ArrayList<>();
        pipelineConfigCopy.getStatus().setConditions(conditions);

        PipelineConfigUtils.dependencyCheck(
            pipelineConfigCopy, pipelineConfigCopy.getStatus().getConditions());
        try {
          if (alreadyHasJobInJenkins && jenkinsClient.hasSyncedJenkinsJob(pipelineConfigCopy)) {
            return new Result(false);
          }

          if (!jenkinsClient.upsertJob(pipelineConfigCopy)) {
            return new Result(false);
          }
        } catch (PipelineConfigConvertException e) {
          logger.warn(
              "[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason {}",
              getControllerName(),
              namespace,
              name,
              StringUtils.join(e.getCauses(), " or "));
          conditions.addAll(ConditionsUtils.convertToConditions(e.getCauses()));
        } catch (IOException e) {
          logger.warn(
              "[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason {}",
              getControllerName(),
              namespace,
              name,
              e.getMessage());
          conditions.add(ConditionsUtils.convertToCondition(e));
        }

        if (pipelineConfigCopy.getStatus().getConditions().size() > 0) {
          pipelineConfigCopy.getStatus().setPhase(PipelineConfigPhase.ERROR);
          DateTime now = DateTime.now();
          pipelineConfigCopy.getStatus().getConditions().forEach(c -> c.setLastAttempt(now));

          if (alreadyHasJobInJenkins) {
            PipelineConfigEvents.newFailedUpdateJobEvent(
                    pipelineConfigCopy,
                    conditions
                        .stream()
                        .map(V1alpha1Condition::getMessage)
                        .collect(Collectors.joining(" or ")))
                .submit();
          } else {
            PipelineConfigEvents.newFailedCreateJobEvent(
                    pipelineConfigCopy,
                    conditions
                        .stream()
                        .map(V1alpha1Condition::getMessage)
                        .collect(Collectors.joining(" or ")))
                .submit();
          }
        } else {
          pipelineConfigCopy.getStatus().setPhase(PipelineConfigPhase.READY);
          if (alreadyHasJobInJenkins) {
            PipelineConfigEvents.newJobUpdatedEvent(
                    pipelineConfigCopy, "Updated Jenkins job successfully")
                .submit();
          } else {
            PipelineConfigEvents.newJobCreatedEvent(
                    pipelineConfigCopy, "Created Jenkins job successfully")
                .submit();
          }
        }

        logger.debug(
            "[{}] Will update PipelineConfig '{}/{}'", getControllerName(), namespace, name);
        PipelineConfigClient pipelineConfigClient =
            (PipelineConfigClient) Clients.get(V1alpha1PipelineConfig.class);
        boolean succeed = pipelineConfigClient.update(pc, pipelineConfigCopy);
        return new Result(!succeed);
      }
    }

    private String getControllerName() {
      return CONTROLLER_NAME;
    }
  }
}
