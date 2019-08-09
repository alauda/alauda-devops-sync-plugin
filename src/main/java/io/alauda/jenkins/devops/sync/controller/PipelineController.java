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
import io.alauda.jenkins.devops.sync.client.PipelineClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.controller.util.InformerUtils;
import io.alauda.jenkins.devops.sync.controller.predicates.BindResourcePredicate;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.CANCELLED;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.QUEUED;

@Extension
public class PipelineController implements ResourceSyncController {

    private static final Logger logger = LoggerFactory.getLogger(PipelineController.class);
    private static final String CONTROLLER_NAME = "PipelineController";

    @Override
    public void add(ControllerManangerBuilder managerBuilder, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        SharedIndexInformer<V1alpha1Pipeline> informer = InformerUtils.getExistingSharedIndexInformer(factory, V1alpha1Pipeline.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(
                    callGeneratorParams -> {
                        try {
                            return api.listPipelineForAllNamespacesCall(
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
                    }, V1alpha1Pipeline.class, V1alpha1PipelineList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
        }


        PipelineClient client = new PipelineClient(informer);
        Clients.register(V1alpha1Pipeline.class, client);

        RateLimitingQueue<Request> rateLimitingQueue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadScheduledExecutor());

        Controller controller =
                ControllerBuilder.defaultBuilder(factory).watch(
                        ControllerBuilder.controllerWatchBuilder(V1alpha1Pipeline.class)
                                .withWorkQueueKeyFunc(pipeline ->
                                        new Request(pipeline.getMetadata().getNamespace(), pipeline.getMetadata().getName()))
                                .withWorkQueue(rateLimitingQueue)
                                .withOnAddFilter(pipeline -> {
                                    logger.debug("[{}] received event: Add, Pipeline '{}/{}'", CONTROLLER_NAME, pipeline.getMetadata().getNamespace(), pipeline.getMetadata().getName());
                                    return true;
                                })
                                .withOnUpdateFilter((oldPipeline, newPipeline) -> {
                                    String namespace = oldPipeline.getMetadata().getNamespace();
                                    String name = oldPipeline.getMetadata().getName();
                                    if (oldPipeline.getMetadata().getResourceVersion().equals(newPipeline.getMetadata().getResourceVersion())) {
                                        logger.debug("[{}] resourceVersion of Pipeline '{}/{}' is equal, will skip update event for it", CONTROLLER_NAME, namespace, name);
                                        return false;
                                    }
                                    logger.debug("[{}] received event: Update, Pipeline '{}/{}'", CONTROLLER_NAME, namespace, name);
                                    return true;
                                })
                                .withOnDeleteFilter((pipeline, aBoolean) -> {
                                    logger.debug("[{}] received event: Delete, Pipeline '{}/{}'", CONTROLLER_NAME, pipeline.getMetadata().getNamespace(), pipeline.getMetadata().getName());
                                    return true;
                                })
                                .build())
                        .withReconciler(new PipelineReconciler(new Lister<>(informer.getIndexer())))
                        .withName(CONTROLLER_NAME)
                        .withReadyFunc(ResourceSyncManager::allRegisteredResourcesSynced)
                        .withWorkerCount(4)
                        .withWorkQueue(rateLimitingQueue)
                        .build();

        managerBuilder.addController(controller);
    }


    static class PipelineReconciler implements Reconciler {

        private Lister<V1alpha1Pipeline> lister;
        private JenkinsClient jenkinsClient;

        public PipelineReconciler(Lister<V1alpha1Pipeline> lister) {
            this.lister = lister;
            jenkinsClient = JenkinsClient.getInstance();
        }

        @Override
        public Result reconcile(Request request) {
            String namespace = request.getNamespace();
            String name = request.getName();

            V1alpha1Pipeline pipeline = lister.namespace(namespace).get(name);
            if (pipeline == null) {
                logger.debug("[{}] Cannot found Pipeline '{}/{}' in local lister, will try to remove it's correspondent Jenkins build", getControllerName(), namespace, name);

                boolean deleteSucceed;
                try {
                    deleteSucceed = jenkinsClient.deletePipeline(new NamespaceName(namespace, name));
                    if (!deleteSucceed) {
                        logger.warn("[{}] Failed to delete job for Pipeline '{}/{}'", getControllerName(), namespace, name);
                    }
                } catch (Exception e) {
                    logger.warn("[{}] Failed to delete job for Pipeline '{}/{}', reason {}", getControllerName(), namespace, name, e.getMessage());
                }
                return new Result(false);
            }


            if (!new BindResourcePredicate().test(pipeline.getSpec().getJenkinsBinding().getName())) {
                logger.debug("[{}] Pipeline '{}/{}' is not bind to correct jenkinsbinding, will skip it", getControllerName(), namespace, name);
                return new Result(false);
            }

            V1alpha1PipelineConfig pipelineConfig = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(pipeline.getSpec().getPipelineConfig().getName());
            if (pipelineConfig == null) {
                logger.error("[{}] Unable to find PipelineConfig for Pipeline '{}/{}'", getControllerName(), namespace, name);
                return new Result(true);
            }

            synchronized (pipeline.getMetadata().getUid().intern()) {
                PipelineClient pipelineClient = (PipelineClient) Clients.get(V1alpha1Pipeline.class);
                V1alpha1Pipeline pipelineCopy = DeepCopyUtils.deepCopy(pipeline);
                if (isNewPipeline(pipelineCopy)) {
                    logger.debug("[{}] Pipeline '{}/{} phase is {}, will trigger a new build", getControllerName(), namespace, name, pipeline.getStatus().getPhase());

                    if (isCreateByJenkins(pipelineCopy)) {
                        logger.debug("[{}] Pipeline created by Jenkins. It should be triggered, skip create event.", getControllerName());
                        pipelineCopy.getStatus().setPhase(QUEUED);
                        boolean succeed = pipelineClient.update(pipeline, pipelineCopy);
                        return new Result(!succeed);
                    }


                    if (AlaudaUtils.isCancellable(pipelineCopy.getStatus()) && AlaudaUtils.isCancelled(pipelineCopy.getStatus())) {
                        pipelineCopy.getStatus().setPhase(CANCELLED);
                        boolean succeed = pipelineClient.update(pipeline, pipelineCopy);
                        return new Result(!succeed);
                    }

                    WorkflowJob job = jenkinsClient.getJob(pipelineCopy, pipelineConfig);
                    if (job == null) {
                        logger.error("[{}] Unable to find Jenkins job for PipelineConfig '{}/{}'", getControllerName(), namespace, pipelineConfig.getMetadata().getName());
                        return new Result(true);
                    }
                    boolean succeed;
                    try {
                        succeed = JenkinsUtils.triggerJob(job, pipelineCopy);
                    } catch (IOException e) {
                        logger.info("[{}] Unable to trigger Pipeline '{}/{}', reason: {}", getControllerName(), namespace, name, e.getMessage());
                        return new Result(true);
                    }
                    if (succeed) {
                        succeed = pipelineClient.update(pipeline, pipelineCopy);
                        return new Result(!succeed);
                    } else {
                        pipelineClient.update(pipeline, pipelineCopy);
                        return new Result(true);
                    }
                }

                if (AlaudaUtils.isCancellable(pipelineCopy.getStatus()) && AlaudaUtils.isCancelled(pipeline.getStatus())) {
                    logger.debug("[{}] Starting cancel Pipeline '{}/{}'", getControllerName(), namespace, name);
                    boolean succeed = jenkinsClient.cancelPipeline(new NamespaceName(namespace, name));
                    if (succeed) {
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

        private String getControllerName() {
            return CONTROLLER_NAME;
        }

        private boolean isNewPipeline(@NotNull V1alpha1Pipeline pipeline) {
            return pipeline.getStatus().getPhase().equals(PipelinePhases.PENDING);
        }

        private boolean isCreateByJenkins(@NotNull V1alpha1Pipeline pipeline) {
            Map<String, String> labels = pipeline.getMetadata().getLabels();
            return (labels != null && Constants.ALAUDA_SYNC_PLUGIN.equals(labels.get(Constants.PIPELINE_CREATED_BY)));
        }
    }
}
