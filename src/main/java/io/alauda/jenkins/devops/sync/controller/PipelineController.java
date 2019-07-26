package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.extend.workqueue.WorkQueue;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Status;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.CANCELLED;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.QUEUED;

@Extension
public class PipelineController extends BaseController<V1alpha1Pipeline, V1alpha1PipelineList> {
    private static final Logger logger = LoggerFactory.getLogger(PipelineController.class);

    private SharedIndexInformer<V1alpha1Pipeline> pipelineInformer;

    private JenkinsClient jenkinsClient = JenkinsClient.getInstance();

    @Override
    public SharedIndexInformer<V1alpha1Pipeline> newInformer(ApiClient client, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        pipelineInformer = factory.sharedIndexInformerFor(
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
        return pipelineInformer;
    }

    @Override
    public void waitControllerReady() throws Exception {
        try {
            PipelineConfigController pipelineConfigController = PipelineConfigController.getCurrentPipelineConfigController();
            if (pipelineConfigController == null) {
                logger.error("Unable to start PipelineController, PipelineConfigController must be initialized first");
                return;
            }

            pipelineConfigController.waitUntilPipelineConfigControllerSyncedAndValid(1000 * 60);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("Unable to start PipelineController, reason {}", e.getMessage());
            throw e;
        }
    }

    @Override
    public EnqueueResourceEventHandler<V1alpha1Pipeline, NamespaceName> newHandler() {
        return new EnqueueResourceEventHandler<V1alpha1Pipeline, NamespaceName>() {
            @Override
            public void onAdd(V1alpha1Pipeline pipeline, WorkQueue<NamespaceName> queue) {
                String pipelineName = pipeline.getMetadata().getName();
                String pipelineNamespace = pipeline.getMetadata().getNamespace();


                logger.debug("PipelineController: received event: ADD, Pipeline '{}/{}'", pipelineNamespace, pipelineName);

                queue.add(new NamespaceName(pipelineNamespace, pipelineName));
            }

            @Override
            public void onUpdate(V1alpha1Pipeline oldPipeline, V1alpha1Pipeline newPipeline, WorkQueue<NamespaceName> queue) {
                String pipelineName = newPipeline.getMetadata().getName();
                String pipelineNamespace = newPipeline.getMetadata().getNamespace();
                if (oldPipeline.getMetadata().getResourceVersion().equals(newPipeline.getMetadata().getResourceVersion())) {
                    logger.debug("ResourceVersion of Pipeline '{}/{}' is equal, will skip update event for it", pipelineNamespace, pipelineName);
                    return;
                }

                logger.debug("PipelineController: received event: Update, Pipeline '{}/{}'", pipelineNamespace, pipelineName);
                queue.add(new NamespaceName(pipelineNamespace, pipelineName));
            }

            @Override
            public void onDelete(V1alpha1Pipeline pipeline, boolean deletedFinalStateUnknown, WorkQueue<NamespaceName> queue) {
                String pipelineName = pipeline.getMetadata().getName();
                String pipelineNamespace = pipeline.getMetadata().getNamespace();

                logger.debug("PipelineController: received event: DELETE, Pipeline '{}/{}'", pipelineNamespace, pipelineName);
                queue.add(new NamespaceName(pipelineNamespace, pipelineName));
            }
        };
    }

    @Override
    public ReconcileResult reconcile(NamespaceName namespaceName) throws Exception {
        String namespace = namespaceName.getNamespace();
        String name = namespaceName.getName();
        ReconcileResult result = new ReconcileResult(false);

        V1alpha1Pipeline pipeline = getPipeline(namespace, name);
        if (pipeline == null) {
            logger.debug("[{}] Cannot found Pipeline '{}/{}' in local lister, will try to remove it's correspondent Jenkins build", getControllerName(), namespace, name);

            boolean deleteSucceed;
            try {
                deleteSucceed = jenkinsClient.deletePipeline(namespaceName);
                if (!deleteSucceed) {
                    logger.warn("[{}] Failed to delete job for Pipeline '{}/{}'", getControllerName(), namespace, name);
                }
            } catch (Exception e) {
                logger.warn("[{}] Failed to delete job for Pipeline '{}/{}', reason {}", getControllerName(), namespace, name, e.getMessage());
            }
            return result;
        }


        if (!JenkinsBindingController.isBindResource(pipeline.getSpec().getJenkinsBinding().getName())) {
            logger.debug("Pipeline '{}/{}' is not bind to correct jenkinsbinding, will skip it", namespace, name);
            return result;
        }

        V1alpha1PipelineConfig pipelineConfig = PipelineConfigController.getCurrentPipelineConfigController()
                .getPipelineConfig(namespace, pipeline.getSpec().getPipelineConfig().getName());
        if (pipelineConfig == null) {
            logger.error("Unable to find PipelineConfig for Pipeline '{}/{}'", namespace, name);
            return result.setRequeue(true);
        }

        synchronized (pipeline.getMetadata().getUid().intern()) {
            V1alpha1Pipeline pipelineCopy = DeepCopyUtils.deepCopy(pipeline);
            if (isNewPipeline(pipelineCopy)) {
                logger.debug("Pipeline '{}/{} phase is {}, will trigger a new build", namespace, name, pipeline.getStatus().getPhase());

                if (isCreateByJenkins(pipelineCopy)) {
                    logger.debug("Pipeline created by Jenkins. It should be triggered, skip create event.");
                    pipelineCopy.getStatus().setPhase(QUEUED);
                    boolean succeed = updatePipeline(pipeline, pipelineCopy);
                    return result.setRequeue(!succeed);
                }


                if (AlaudaUtils.isCancellable(pipelineCopy.getStatus()) && AlaudaUtils.isCancelled(pipelineCopy.getStatus())) {
                    pipelineCopy.getStatus().setPhase(CANCELLED);
                    boolean succeed = updatePipeline(pipeline, pipelineCopy);
                    return result.setRequeue(!succeed);
                }

                WorkflowJob job = jenkinsClient.getJob(pipelineCopy, pipelineConfig);
                if (job == null) {
                    logger.error("Unable to find Jenkins job for PipelineConfig '{}/{}'", namespace, pipelineConfig.getMetadata().getName());
                    return result.setRequeue(true);
                }
                boolean succeed = JenkinsUtils.triggerJob(job, pipelineCopy);
                if (succeed) {
                    succeed = updatePipeline(pipeline, pipelineCopy);
                    return result.setRequeue(!succeed);
                } else {
                    updatePipeline(pipeline, pipelineCopy);
                    return result.setRequeue(true);
                }
            }

            if (AlaudaUtils.isCancellable(pipelineCopy.getStatus()) && AlaudaUtils.isCancelled(pipeline.getStatus())) {
                logger.debug("Starting cancel Pipeline '{}/{}'", namespace, name);
                boolean succeed = jenkinsClient.cancelPipeline(namespaceName);
                if (succeed) {
                    succeed = updatePipeline(pipeline, pipelineCopy);
                    return result.setRequeue(!succeed);
                } else {
                    updatePipeline(pipeline, pipelineCopy);
                    return result.setRequeue(true);
                }
            }

            return result;
        }
    }

    @Override
    public String getControllerName() {
        return "PipelineController";
    }

    @Override
    public boolean hasSynced() {
        return pipelineInformer != null && pipelineInformer.hasSynced();
    }

    @Override
    public Type getType() {
        return new TypeToken<V1alpha1Pipeline>() {
        }.getType();
    }

    private boolean isNewPipeline(@NotNull V1alpha1Pipeline pipeline) {
        return pipeline.getStatus().getPhase().equals(PipelinePhases.PENDING);
    }

    private boolean isCreateByJenkins(@NotNull V1alpha1Pipeline pipeline) {
        Map<String, String> labels = pipeline.getMetadata().getLabels();
        return (labels != null && Constants.ALAUDA_SYNC_PLUGIN.equals(labels.get(Constants.PIPELINE_CREATED_BY)));
    }


    public static boolean updatePipeline(V1alpha1Pipeline oldPipeline, V1alpha1Pipeline newPipeline) {
        String name = oldPipeline.getMetadata().getName();
        String namespace = newPipeline.getMetadata().getNamespace();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldPipeline, newPipeline);
        } catch (IOException e) {
            logger.warn("Unable to generate patch for Pipeline '{}/{}', reason: {}",
                    namespace, name, e.getMessage());
            return false;
        }
        List<JsonObject> body = new LinkedList<>();
        JsonArray arr = new Gson().fromJson(patch, JsonArray.class);
        arr.forEach(jsonElement -> body.add(jsonElement.getAsJsonObject()));

        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        try {
            api.patchNamespacedPipeline(
                    name,
                    namespace,
                    body,
                    null,
                    null);
        } catch (ApiException e) {
            logger.warn("Unable to patch Pipeline '{}/{}', reason: {}",
                    namespace, name, e.getMessage());
            return false;
        }

        return true;
    }

    public V1alpha1Pipeline getPipeline(String namespace, String name) {
        Lister<V1alpha1Pipeline> lister = new Lister<>(pipelineInformer.getIndexer());
        return lister.namespace(namespace).get(name);
    }

    public List<V1alpha1Pipeline> listPipelines(String namespace) {
        Lister<V1alpha1Pipeline> lister = new Lister<>(pipelineInformer.getIndexer());
        return lister.namespace(namespace).list();
    }

    /**
     * Get current running PipelineConfigController
     *
     * @return null if there is no active JenkinsBindingController
     */
    public static PipelineController getCurrentPipelineController() {
        ExtensionList<PipelineController> pipelineControllers = ExtensionList.lookup(PipelineController.class);

        if (pipelineControllers.size() > 1) {
            logger.warn("There are more than two PipelineController exist, maybe a potential bug");
        }

        return pipelineControllers.get(0);
    }

    public static V1alpha1Pipeline createPipeline(String namespace, V1alpha1Pipeline pipe) throws ApiException {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        return api.createNamespacedPipeline(namespace, pipe, null, null, null);
    }

    public static V1Status deletePipeline(String namespace, String name) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        try {
            return api.deleteNamespacedPipeline(name, namespace, new V1DeleteOptions(), null, null, null, null, null);
        } catch (ApiException e) {
            logger.warn("Unable to delete pipeline '{}/{}', reason: {}", namespace, name, e.getMessage());
            return null;
        }
    }
}
