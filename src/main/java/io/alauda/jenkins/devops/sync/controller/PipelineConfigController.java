package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.extend.controller.Controller;
import io.alauda.devops.java.client.extend.controller.builder.ControllerBuilder;
import io.alauda.devops.java.client.extend.controller.builder.ControllerManangerBuilder;
import io.alauda.devops.java.client.extend.controller.reconciler.Reconciler;
import io.alauda.devops.java.client.extend.controller.reconciler.Request;
import io.alauda.devops.java.client.extend.controller.reconciler.Result;
import io.alauda.devops.java.client.extend.workqueue.DefaultRateLimitingQueue;
import io.alauda.devops.java.client.extend.workqueue.RateLimitingQueue;
import io.alauda.devops.java.client.models.*;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.client.PipelineConfigClient;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.controller.util.InformerUtils;
import io.alauda.jenkins.devops.sync.controller.predicates.BindResourcePredicate;
import io.alauda.jenkins.devops.sync.exception.ConditionsUtils;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Extension
public class PipelineConfigController implements ResourceSyncController {

    private static final Logger logger = LoggerFactory.getLogger(PipelineConfigController.class);
    private static final String CONTROLLER_NAME = "PipelineConfigController";

    @Override
    public void add(ControllerManangerBuilder managerBuilder, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        SharedIndexInformer<V1alpha1PipelineConfig> informer = InformerUtils.getExistingSharedIndexInformer(factory, V1alpha1PipelineConfig.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(
                    callGeneratorParams -> {
                        try {
                            return api.listPipelineConfigForAllNamespacesCall(
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
                                    null
                            );
                        } catch (ApiException e) {
                            throw new RuntimeException(e);
                        }
                    }, V1alpha1PipelineConfig.class, V1alpha1PipelineConfigList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
        }


        PipelineConfigClient client = new PipelineConfigClient(informer);
        Clients.register(V1alpha1PipelineConfig.class, client);

        RateLimitingQueue<Request> rateLimitingQueue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadScheduledExecutor());

        Controller controller =
                ControllerBuilder.defaultBuilder(factory).watch(
                        ControllerBuilder.controllerWatchBuilder(V1alpha1PipelineConfig.class)
                                .withWorkQueueKeyFunc(pipelineConfig ->
                                        new Request(pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName()))
                                .withWorkQueue(rateLimitingQueue)
                                .withOnAddFilter(pipelineConfig -> {
                                    if (pipelineConfig.getStatus().getPhase().equals(PipelineConfigPhase.CREATING)) {
                                        logger.debug("[{}] phase of PipelineConfig '{}/{}' is {}, will skip it", CONTROLLER_NAME, pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName(), PipelineConfigPhase.CREATING);
                                        return false;
                                    }

                                    logger.debug("[{}] receives event: Add; PipelineConfig '{}/{}'",
                                            CONTROLLER_NAME,
                                            pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName());
                                    return true;
                                })
                                .withOnUpdateFilter((oldPipelineConfig, newPipelineConfig) -> {
                                    String namespace = oldPipelineConfig.getMetadata().getNamespace();
                                    String name = oldPipelineConfig.getMetadata().getName();
                                    if (oldPipelineConfig.getMetadata().getResourceVersion().equals(newPipelineConfig.getMetadata().getResourceVersion())) {
                                        logger.debug("[{}] resourceVersion of PipelineConfig '{}/{}' is equal, will skip update event for it", CONTROLLER_NAME, namespace, name);
                                        return false;
                                    }

                                    if (newPipelineConfig.getStatus().getPhase().equals(PipelineConfigPhase.CREATING)) {
                                        logger.debug("[{}] phase of PipelineConfig '{}/{}' is {}, will skip it", CONTROLLER_NAME, namespace, name, PipelineConfigPhase.CREATING);
                                        return false;
                                    }

                                    logger.debug("[{}] receives event: Update; PipelineConfig '{}/{}'",
                                            CONTROLLER_NAME,
                                            namespace, name);

                                    return true;
                                })
                                .withOnDeleteFilter((pipelineConfig, aBoolean) -> {
                                    logger.debug("[{}] receives event: Add; PipelineConfig '{}/{}'",
                                            CONTROLLER_NAME,
                                            pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName());
                                    return true;
                                }).build())
                        .withReconciler(new PipelineConfigReconciler(new Lister<>(informer.getIndexer())))
                        .withName(CONTROLLER_NAME)
                        .withReadyFunc(ResourceSyncManager::allRegisteredResourcesSynced)
                        .withWorkerCount(4)
                        .withWorkQueue(rateLimitingQueue)
                        .build();

        managerBuilder.addController(controller);
    }

    static class PipelineConfigReconciler implements Reconciler {
        private Lister<V1alpha1PipelineConfig> lister;
        private JenkinsClient jenkinsClient;

        public PipelineConfigReconciler(Lister<V1alpha1PipelineConfig> lister) {
            this.lister = lister;
            this.jenkinsClient = JenkinsClient.getInstance();
        }

        @Override
        public Result reconcile(Request request) {
            String namespace = request.getNamespace();
            String name = request.getName();

            V1alpha1PipelineConfig pc = lister.namespace(namespace).get(name);
            if (pc == null) {
                logger.debug("[{}] Cannot found PipelineConfig '{}/{}' in local lister, will try to remove it's correspondent Jenkins job", getControllerName(), namespace, name);
                boolean deleteSucceed;
                try {
                    deleteSucceed = jenkinsClient.deleteJob(new NamespaceName(namespace, name));
                    if (!deleteSucceed) {
                        logger.warn("[{}] Failed to delete job for PipelineConfig '{}/{}'", getControllerName(), namespace, name);
                    }
                } catch (IOException | InterruptedException e) {
                    logger.warn("[{}] Failed to delete job for PipelineConfig '{}/{}', reason {}", getControllerName(), namespace, name, e.getMessage());
                }
                return new Result(false);
            }


            if (!new BindResourcePredicate().test(pc.getSpec().getJenkinsBinding().getName())) {
                logger.debug("[{}] PipelineConfigController: {}/{}' is not bind to correct jenkinsbinding, will skip it", getControllerName(), namespace, name);
                return new Result(false);
            }

            logger.debug("[{}] Start to create or update Jenkins job for PipelineConfig '{}/{}'", getControllerName(), namespace, name);
            V1alpha1PipelineConfig pipelineConfigCopy = DeepCopyUtils.deepCopy(pc);

            synchronized (pc.getMetadata().getUid().intern()) {
                // clean conditions first, any error info will be put it into conditions
                List<V1alpha1Condition> conditions = new ArrayList<>();
                pipelineConfigCopy.getStatus().setConditions(conditions);

                PipelineConfigUtils.dependencyCheck(pipelineConfigCopy, pipelineConfigCopy.getStatus().getConditions());
                try {
                    if (jenkinsClient.hasSyncedJenkinsJob(pipelineConfigCopy)) {
                        return new Result(false);
                    }

                    jenkinsClient.upsertJob(pipelineConfigCopy);
                } catch (PipelineConfigConvertException e) {
                    logger.warn("[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason {}", getControllerName(), namespace, name, StringUtils.join(e.getCauses(), " or "));
                    conditions.addAll(ConditionsUtils.convertToConditions(e.getCauses()));
                } catch (IOException e) {
                    logger.warn("[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason {}", getControllerName(), namespace, name, e.getMessage());
                    conditions.add(ConditionsUtils.convertToCondition(e));
                }

                if (pipelineConfigCopy.getStatus().getConditions().size() > 0) {
                    pipelineConfigCopy.getStatus().setPhase(PipelineConfigPhase.ERROR);
                    DateTime now = DateTime.now();
                    pipelineConfigCopy.getStatus().getConditions().forEach(c -> c.setLastAttempt(now));
                } else {
                    pipelineConfigCopy.getStatus().setPhase(PipelineConfigPhase.READY);
                }

                logger.debug("[{}] Will update PipelineConfig '{}/{}'", getControllerName(), namespace, name);
                PipelineConfigClient pipelineConfigClient = (PipelineConfigClient) Clients.get(V1alpha1PipelineConfig.class);
                boolean succeed = pipelineConfigClient.update(pc, pipelineConfigCopy);
                return new Result(!succeed);
            }
        }

        private String getControllerName() {
            return CONTROLLER_NAME;
        }
    }
}
