package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.security.ACL;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineList;
import io.alauda.devops.java.client.models.V1alpha1PipelineStatus;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.support.controller.Controller;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1OwnerReference;
import io.kubernetes.client.models.V1Status;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.QUEUED;
import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.updatePipelinePhase;

@Extension
public class PipelineController implements Controller<V1alpha1Pipeline, V1alpha1PipelineList> {
    private static final Logger logger = Logger.getLogger(PipelineController.class.getName());

    private static final HashSet<V1alpha1Pipeline> pipelinesWithNoPCList = new HashSet<>();
    private SharedIndexInformer<V1alpha1Pipeline> pipelineInformer;

    @Override
    public void initialize(ApiClient apiClient, SharedInformerFactory sharedInformerFactory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        pipelineInformer = sharedInformerFactory.sharedIndexInformerFor(
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
                }, V1alpha1Pipeline.class, V1alpha1PipelineList.class);
    }

    @Override
    public void start() {
        try {
            PipelineConfigController pipelineConfigController = PipelineConfigController.getCurrentPipelineConfigController();
            if (pipelineConfigController == null) {
                logger.log(Level.SEVERE, "Unable to start PipelineController, PipelineConfigController must be initialized first");
                return;
            }

            pipelineConfigController.waitUntilPipelineConfigControllerSyncedAndValid(1000 * 60);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, String.format("Unable to start PipelineController, reason %s", e.getMessage()), e);
            return;
        }
        PipelineController.flushPipelinesWithNoPCList();

        pipelineInformer.addEventHandler(new ResourceEventHandler<V1alpha1Pipeline>() {
            @Override
            public void onAdd(V1alpha1Pipeline pipeline) {
                String pipelineName = pipeline.getMetadata().getName();
                String pipelineNamespace = pipeline.getMetadata().getNamespace();
                if (!JenkinsBindingController.isBindResource(pipeline.getSpec().getJenkinsBinding().getName())) {
                    logger.log(Level.FINE,
                            String.format("Pipeline '%s/%s' is not bind to correct jenkinsbinding, will skip it", pipelineNamespace, pipelineName));
                    return;
                }

                logger.log(Level.FINE, String.format("PipelineController: received event: ADD, Pipeline '%s/%s'", pipelineNamespace, pipelineName));
                if (!isNewPipeline(pipeline)) {
                    logger.log(Level.FINE, String.format("Pipeline '%s/%s phase is %s, will not add it", pipelineNamespace, pipelineName, pipeline.getStatus().getPhase()));
                    return;
                }

                if (isCreateByJenkins(pipeline)) {
                    updatePipelinePhase(pipeline, QUEUED);
                    logger.fine(() -> "Pipeline created by Jenkins. It should be triggered, skip create event.");
                    return;
                }

                try {
                    pipeline = DeepCopyUtils.deepCopy(pipeline);
                    addEventToJenkinsJobRun(pipeline);
                } catch (IOException e) {
                    logger.log(Level.WARNING, String.format("Failde to add pipeline '%s/%s, reason: %s'", pipelineNamespace, pipelineName, e.getMessage()), e);
                }
            }

            @Override
            public void onUpdate(V1alpha1Pipeline oldPipeline, V1alpha1Pipeline newPipeline) {
                String pipelineName = newPipeline.getMetadata().getName();
                String pipelineNamespace = newPipeline.getMetadata().getNamespace();
                if (!JenkinsBindingController.isBindResource(newPipeline.getSpec().getJenkinsBinding().getName())) {
                    logger.log(Level.FINE,
                            String.format("Pipeline '%s/%s' is not bind to correct jenkinsbinding, will skip it", pipelineNamespace, pipelineName));
                    return;
                }


                logger.log(Level.FINE, String.format("PipelineController: received event: Update, Pipeline '%s/%s'", pipelineNamespace, pipelineName));
                modifyEventToJenkinsJobRun(newPipeline);
            }

            @Override
            public void onDelete(V1alpha1Pipeline pipeline, boolean deletedFinalStateUnknown) {
                String pipelineName = pipeline.getMetadata().getName();
                String pipelineNamespace = pipeline.getMetadata().getNamespace();
                if (!JenkinsBindingController.isBindResource(pipeline.getSpec().getJenkinsBinding().getName())) {
                    logger.log(Level.FINE,
                            String.format("Pipeline '%s/%s' is not bind to correct jenkinsbinding, will skip it", pipelineNamespace, pipelineName));
                    return;
                }

                logger.log(Level.FINE, String.format("PipelineController: received event: DELETE, Pipeline '%s/%s'", pipelineNamespace, pipelineName));
                try {
                    deleteEventToJenkinsJobRun(pipeline);
                } catch (Exception e) {
                    logger.log(Level.WARNING, String.format("Failde to delete pipeline '%s/%s, reason: %s'", pipelineNamespace, pipelineName, e.getMessage()), e);
                }
            }
        });
    }

    @Override
    public void shutDown(Throwable throwable) {
        if (pipelineInformer == null) {
            return;
        }

        try {
            pipelineInformer.stop();
            pipelineInformer = null;
        } catch (Throwable e) {
            logger.log(Level.WARNING, String.format("Unable to stop PipelineController, reason: %s", e.getMessage()));
        }
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


    // trigger any builds whose watch events arrived before the
    // corresponding build config watch events
    public static void flushPipelinesWithNoPCList() {
        HashSet<V1alpha1Pipeline> clone = (HashSet<V1alpha1Pipeline>) pipelinesWithNoPCList.clone();
        clearNoPCList();
        for (V1alpha1Pipeline pipeline : clone) {
            WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
            logger.fine("Pipeline flush: " + pipeline.getMetadata().getName() + " - job: " + job);
            if (job != null) {
                try {
                    logger.info("triggering job run for previously skipped pipeline "
                            + pipeline.getMetadata().getName());
                    JenkinsUtils.triggerJob(job, pipeline);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "flushCachedPipelines", e);
                }
            } else {
                addPipelineToNoPCList(pipeline);
            }
        }

    }

    public static void addPipelineToNoPCList(V1alpha1Pipeline pipeline) {
        // should have been caught upstack, but just in case since public method
        if (!AlaudaUtils.isPipelineStrategyPipeline(pipeline))
            return;
        pipelinesWithNoPCList.add(pipeline);
    }

    private static synchronized void removePipelineFromNoPCList(V1alpha1Pipeline pipeline) {
        pipelinesWithNoPCList.remove(pipeline);
    }

    private static synchronized void clearNoPCList() {
        pipelinesWithNoPCList.clear();
    }

    private boolean isNewPipeline(@NotNull V1alpha1Pipeline pipeline) {
        return pipeline.getStatus().getPhase().equals(PipelinePhases.PENDING);
    }

    private boolean isCreateByJenkins(@NotNull V1alpha1Pipeline pipeline) {
        Map<String, String> labels = pipeline.getMetadata().getLabels();
        return (labels != null && Constants.ALAUDA_SYNC_PLUGIN.equals(labels.get(Constants.PIPELINE_CREATED_BY)));
    }


    public static void updatePipeline(V1alpha1Pipeline oldPipeline, V1alpha1Pipeline newPipeline) {
        String name = oldPipeline.getMetadata().getName();
        String namespace = newPipeline.getMetadata().getNamespace();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldPipeline, newPipeline);
        } catch (IOException e) {
            logger.log(Level.WARNING, String.format("Unable to generate patch for Pipeline '%s/%s', reason: %s",
                    namespace, name, e.getMessage()), e);
            return;
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
            logger.log(Level.WARNING, String.format("Unable to patch Pipeline '%s/%s', reason: %s",
                    namespace, name, e.getMessage()), e);
        }
    }


    public static synchronized boolean addEventToJenkinsJobRun(V1alpha1Pipeline pipeline)
            throws IOException {
        String pipelineName = pipeline.getMetadata().getName();
        String pipelineNamespace = pipeline.getMetadata().getNamespace();

        // should have been caught upstack, but just in case since public method
        if (!AlaudaUtils.isPipelineStrategyPipeline(pipeline))
            return false;
        V1alpha1PipelineStatus status = pipeline.getStatus();
        if (status != null) {
            logger.info(String.format("Pipeline %s/%s Status is not null: %s", pipelineNamespace, pipelineName, status));
            if (AlaudaUtils.isCancelled(status)) {
                logger.info(String.format("Pipeline %s/%s Status is Cancelled... updating pipeline: %s", pipelineNamespace, pipelineName, status));
                AlaudaUtils.updatePipelinePhase(pipeline, PipelinePhases.CANCELLED);
                return false;
            }
            if (!AlaudaUtils.isNew(status)) {
                logger.info(String.format("Pipeline %s/%s is not new... cancelling... %s", pipelineName, pipelineNamespace, status));
                return false;
            }
        }

        WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
        logger.info("Pipeline got job... " + job);
        if (job != null) {
            logger.info(String.format("Pipeline job will trigger... %s pipeline: %s/%s", job.getName(), pipelineNamespace, pipelineName));
            return JenkinsUtils.triggerJob(job, pipeline);
        }

        logger.info(String.format("skipping watch event for pipeline %s/%s no job at this time", pipelineNamespace, pipelineName));
        addPipelineToNoPCList(pipeline);
        return false;
    }

    private static void modifyEventToJenkinsJobRun(V1alpha1Pipeline pipeline) {
        String pipelineNamespace = pipeline.getMetadata().getNamespace();
        String pipelineName = pipeline.getMetadata().getName();

        V1alpha1PipelineStatus status = pipeline.getStatus();
        logger.info(String.format("Modified pipeline %s/%s", pipelineNamespace, pipelineName));
        if (status != null && AlaudaUtils.isCancellable(status) && AlaudaUtils.isCancelled(status)) {
            logger.info(String.format("Pipeline %s/%s was cancelled", pipelineNamespace, pipelineName));
            WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
            if (job != null) {
                JenkinsUtils.cancelPipeline(job, pipeline);
            } else {
                removePipelineFromNoPCList(pipeline);
            }
        } else {
            logger.info(String.format("Pipeline changed... flusing pipelines... %s/%s", pipelineNamespace, pipelineName));
            // see if any pre-BC cached builds can now be flushed
            flushPipelinesWithNoPCList();
        }
    }

    // in response to receiving an Alauda DevOps delete pipeline event, this method
    // will drive
    // the clean up of the Jenkins job run the pipeline is mapped one to one with;
    // as part of that
    // clean up it will synchronize with the pipeline config event watcher to
    // handle pipeline config
    // delete events and pipeline delete events that arrive concurrently and in a
    // nondeterministic
    // order
    private static synchronized void deleteEventToJenkinsJobRun(
            final V1alpha1Pipeline pipeline) throws Exception {
        logger.info("Pipeline delete: " + pipeline.getMetadata().getName());
        List<V1OwnerReference> ownerRefs = pipeline.getMetadata().getOwnerReferences();
        for (V1OwnerReference ref : ownerRefs) {
            if ("PipelineConfig".equals(ref.getKind()) && ref.getUid() != null
                    && ref.getUid().length() > 0) {
                // employ intern to facilitate sync'ing on the same actual
                // object
                String pcUid = ref.getUid().intern();
                synchronized (pcUid) {
                    // if entire job already deleted via bc delete, just return
                    if (PipelineConfigToJobMap.getJobFromPipelineConfigUid(pcUid) == null)
                        return;
                    innerDeleteEventToJenkinsJobRun(pipeline);
                    return;
                }
            }
        }
        // otherwise, if something odd is up and there is no parent BC, just
        // clean up
        innerDeleteEventToJenkinsJobRun(pipeline);
    }

    // innerDeleteEventToJenkinsJobRun is the actual delete logic at the heart
    // of deleteEventToJenkinsJobRun
    // that is either in a sync block or not based on the presence of a BC uid
    private static synchronized void innerDeleteEventToJenkinsJobRun(
            final V1alpha1Pipeline pipeline) throws Exception {
        final WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
        if (job != null) {
            ACL.impersonate(ACL.SYSTEM,
                    new NotReallyRoleSensitiveCallable<Void, Exception>() {
                        @Override
                        public Void call() throws Exception {
                            JenkinsUtils.cancelPipeline(job, pipeline, true);

                            JenkinsUtils.deleteRun(job, pipeline);

                            return null;
                        }
                    });
        } else {
            // in case pipeline was created and deleted quickly, prior to seeing BC
            // event, clear out from pre-BC cache
            removePipelineFromNoPCList(pipeline);
        }
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
            logger.log(Level.WARNING, "There are more than two PipelineController exist, maybe a potential bug");
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
            logger.log(Level.WARNING, String.format("Unable to delete pipeline '%s/%s', reason: %s", namespace, name, e.getMessage()), e);
            return null;
        }
    }
}
