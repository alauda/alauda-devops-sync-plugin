package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigList;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigStatus;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.support.controller.Controller;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigConvert;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.controller.util.Wait;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Status;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class PipelineConfigController implements Controller<V1alpha1PipelineConfig, V1alpha1PipelineConfigList> {
    private static final Logger logger = Logger.getLogger(PipelineConfigController.class.getName());
    private SharedIndexInformer<V1alpha1PipelineConfig> pipelineConfigInformer;

    // for coordinating between ItemListener.onUpdate and onDeleted both
    // getting called when we delete a job; ID should be combo of namespace
    // and name for BC to properly differentiate; we don't use UUID since
    // when we filter on the ItemListener side the UUID may not be
    // available
    private static final HashSet<String> deletesInProgress = new HashSet<>();

    private volatile boolean dependentControllerSynced = false;

    @Override
    public void initialize(ApiClient apiClient, SharedInformerFactory sharedInformerFactory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        pipelineConfigInformer = sharedInformerFactory.sharedIndexInformerFor(
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
                }, V1alpha1PipelineConfig.class, V1alpha1PipelineConfigList.class);
    }

    @Override
    public void start() {
        try {
            JenkinsBindingController jenkinsBindingController = JenkinsBindingController.getCurrentJenkinsBindingController();
            if (jenkinsBindingController == null) {
                logger.log(Level.SEVERE, "Unable to start PipelineConfigController, JenkinsBindingController must be initialized first");
                return;
            }
            CodeRepositoryController codeRepositoryController = CodeRepositoryController.getCurrentCodeRepositoryController();
            if (codeRepositoryController == null) {
                logger.log(Level.SEVERE, "Unable to start PipelineConfigController, CodeRepository must be initialized first");
                return;
            }

            jenkinsBindingController.waitUntilJenkinsBindingControllerSyncedAndValid(1000 * 60);
            codeRepositoryController.waitUntilCodeRepositoryControllerSynced(1000 * 60);
            dependentControllerSynced = true;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, String.format("Unable to start PipelineConfigController, reason %s", e.getMessage()), e);
            return;
        }

        PipelineConfigToJobMap.initializePipelineConfigToJobMap();

        // Add event handler in start method to ensure all controllers initialized
        // TODO If we use workqueue in the future, we should move this callback back to initialize method, and use workqueue to handle event here.
        pipelineConfigInformer.addEventHandler(new ResourceEventHandler<V1alpha1PipelineConfig>() {
            @Override
            public void onAdd(V1alpha1PipelineConfig pipelineConfig) {
                List<String> namespaces = JenkinsBindingController.getCurrentJenkinsBindingController().getBindingNamespaces();
                String pipelineConfigName = pipelineConfig.getMetadata().getName();
                String pipelineConfigNamespace = pipelineConfig.getMetadata().getNamespace();
                logger.log(Level.FINE,
                        String.format("PipelineConfigController receives event: Add; PipelineConfig '%s/%s'",
                                pipelineConfigNamespace, pipelineConfigName));

                if (!namespaces.contains(pipelineConfig.getMetadata().getNamespace())) {
                    logger.log(Level.WARNING,
                            String.format("PipelineConfig '%s/%s' not exists in namespace that has correct jenkinsbinding, will skip it", pipelineConfigNamespace, pipelineConfigName));
                    return;
                }

                try {
                    pipelineConfig = DeepCopyUtils.deepCopy(pipelineConfig);
                    upsertJob(pipelineConfig);
                } catch (Exception e) {
                    logger.log(Level.SEVERE,
                            String.format("Failed to update Jenkins job for PipelineConfig '%s/%s'",
                                    pipelineConfigNamespace, pipelineConfigName));
                }
            }

            @Override
            public void onUpdate(V1alpha1PipelineConfig oldPipelineConfig, V1alpha1PipelineConfig newPipelineConfig) {
                List<String> namespaces = JenkinsBindingController.getCurrentJenkinsBindingController().getBindingNamespaces();
                String pipelineConfigName = newPipelineConfig.getMetadata().getName();
                String pipelineConfigNamespace = newPipelineConfig.getMetadata().getNamespace();

                logger.log(Level.FINE,
                        String.format("PipelineConfigController receives event: Update; PipelineConfig '%s/%s'",
                                pipelineConfigNamespace, pipelineConfigName));

                if (!namespaces.contains(newPipelineConfig.getMetadata().getNamespace())) {
                    logger.log(Level.FINE,
                            String.format("PipelineConfig '%s/%s' not exists in namespace has correct jenkinsbinding, will skip it", pipelineConfigNamespace, pipelineConfigName));
                    return;
                }

                try {
                    newPipelineConfig = DeepCopyUtils.deepCopy(newPipelineConfig);
                    modifyEventToJenkinsJob(newPipelineConfig);
                } catch (Exception e) {
                    logger.log(Level.SEVERE, String.format("Failed to update Jenkins job for PipelineConfig '%s/%s'", pipelineConfigNamespace, pipelineConfigName));
                }
            }

            @Override
            public void onDelete(V1alpha1PipelineConfig pipelineConfig, boolean deletedFinalStateUnknown) {
                String pipelineConfigName = pipelineConfig.getMetadata().getName();
                String pipelineConfigNamespace = pipelineConfig.getMetadata().getNamespace();

                TopLevelItem item = PipelineConfigToJobMap.getItemByPC(pipelineConfig);
                if (item != null) {
                    AlaudaJobProperty pro = PipelineConfigToJobMap.getProperty(item);
                    if (pro != null && pipelineConfig.getMetadata().getUid().equals(pro.getUid())) {
                        try {
                            deleteEventToJenkinsJob(pipelineConfig);
                        } catch (Exception e) {
                            logger.log(Level.SEVERE, String.format("Failed to update Jenkins job for PipelineConfig '%s/%s'", pipelineConfigNamespace, pipelineConfigName));
                        }
                    }
                }
            }
        });
    }

    @Override
    public void shutDown(Throwable throwable) {
        if (pipelineConfigInformer == null) {
            return;
        }

        try {
            pipelineConfigInformer.stop();
            pipelineConfigInformer = null;
        } catch (Throwable e) {
            logger.log(Level.WARNING, String.format("Unable to stop PipelineConfigController, reason: %s", e.getMessage()));
        }
    }

    @Override
    public boolean hasSynced() {
        return pipelineConfigInformer != null && pipelineConfigInformer.hasSynced();
    }

    public boolean isValid() {
        return hasSynced() && dependentControllerSynced;
    }

    @Override
    public Type getType() {
        return new TypeToken<V1alpha1PipelineConfig>() {
        }.getType();
    }

    /**
     * Wait until PipelineConfigController synced
     *
     * @param timeout milliseconds
     */
    public void waitUntilPipelineConfigControllerSyncedAndValid(long timeout) throws InterruptedException, ExecutionException, TimeoutException {
        if (hasSynced()) {
            return;
        }
        Wait.waitUntil(this, PipelineConfigController::isValid, 500, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Get current running PipelineConfigController
     *
     * @return null if there is no active JenkinsBindingController
     */
    public static PipelineConfigController getCurrentPipelineConfigController() {
        ExtensionList<PipelineConfigController> pipelineConfigControllers = ExtensionList.lookup(PipelineConfigController.class);

        if (pipelineConfigControllers.size() > 1) {
            logger.log(Level.WARNING, "There are more than two PipelineConfigController exist, maybe a potential bug");
        }

        return pipelineConfigControllers.get(0);
    }


    /**
     * Update or create PipelineConfig
     *
     * @param pipelineConfig PipelineConfig
     * @throws Exception in case of io error
     */
    private void upsertJob(final V1alpha1PipelineConfig pipelineConfig) throws Exception {
        V1alpha1PipelineConfigStatus pipelineConfigStatus = pipelineConfig.getStatus();
        String pipelineConfigPhase = null;
        if (pipelineConfigStatus == null || !PipelineConfigPhase.SYNCING.equals(
                (pipelineConfigPhase = pipelineConfig.getStatus().getPhase()))) {
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    String jobFullname = AlaudaUtils.jenkinsJobFullName(pipelineConfig);
                    Item item = Jenkins.getInstance().getItemByFullName(jobFullname);

                    if (item instanceof WorkflowJob || item instanceof WorkflowMultiBranchProject) {
                        PipelineConfigToJobMap.putJobWithPipelineConfig(((TopLevelItem) item), pipelineConfig);
                    } else {
                        logger.log(Level.WARNING, String.format("Unable to find mapped job in Jenkins for PipelineConfig '%s/%s'", pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName()));
                    }
                    return null;
                }
            });

            logger.log(Level.FINE, String.format("Do nothing, PipelineConfig [%s], phase [%s].",
                    pipelineConfig.getMetadata().getName(), pipelineConfigPhase));
            return;
        }

        // clean conditions first, any error info will be put it into conditions
        List<V1alpha1Condition> conditions = new ArrayList<>();
        pipelineConfig.getStatus().setConditions(conditions);

        // check plugin dependency
        PipelineConfigUtils.dependencyCheck(pipelineConfig, conditions);

        if (AlaudaUtils.isPipelineStrategyPipelineConfig(pipelineConfig)) {
            // sync on intern of name should guarantee sync on same actual obj
            synchronized (pipelineConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() {
                        ExtensionList<PipelineConfigConvert> convertList = Jenkins.getInstance().getExtensionList(PipelineConfigConvert.class);
                        Optional<PipelineConfigConvert> optional = convertList.stream().filter(convert -> convert.accept(pipelineConfig)).findFirst();
                        if (optional.isPresent()) {
                            PipelineConfigConvert convert = optional.get();

                            try {
                                convert.convert(pipelineConfig);
                                // if create job successfully,
                                PipelineController.flushPipelinesWithNoPCList();
                            } catch (Exception e) {
                                logger.log(Level.SEVERE,
                                        String.format("Unable to convert PipelineConfig '%s/%s' to job, reason: %s",
                                                pipelineConfig.getMetadata().getNamespace(),
                                                pipelineConfig.getMetadata().getName(),
                                                e.getMessage()), e);


                                V1alpha1Condition condition = new V1alpha1Condition();
                                condition.setReason(ErrorMessages.FAIL_TO_CREATE);
                                condition.setMessage(e.getMessage());
                                pipelineConfig.getStatus().getConditions().add(condition);

                                convert.updatePipelineConfigPhase(pipelineConfig);
                            }
                        } else {
                            logger.log(Level.WARNING, String.format("Can't handle this kind of PipelineConfig '%s/%s'",
                                    pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName()));
                        }

                        return null;
                    }
                });
            }
        }
    }

    private synchronized void modifyEventToJenkinsJob(V1alpha1PipelineConfig pipelineConfig) throws Exception {
        if (AlaudaUtils.isPipelineStrategyPipelineConfig(pipelineConfig)) {
            upsertJob(pipelineConfig);
        }
    }

    // in response to receiving an alauda delete build config event, this
    // method will drive
    // the clean up of the Jenkins job the build config is mapped one to one
    // with; as part of that
    // clean up it will synchronize with the build event watcher to handle build
    // config
    // delete events and build delete events that arrive concurrently and in a
    // nondeterministic
    // order
    private synchronized void deleteEventToJenkinsJob(final V1alpha1PipelineConfig pipelineConfig) throws Exception {
        String pcUid = pipelineConfig.getMetadata().getUid();
        if (pcUid != null && pcUid.length() > 0) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            pcUid = pcUid.intern();
            innerDeleteEventToJenkinsJob(pipelineConfig);
            return;
        }
        // uid should not be null / empty, but just in case, still clean up
        innerDeleteEventToJenkinsJob(pipelineConfig);
    }

    public static void updatePipelineConfig(V1alpha1PipelineConfig oldPipelineConfig, V1alpha1PipelineConfig newPipelineConfig) {
        String name = oldPipelineConfig.getMetadata().getName();
        String namespace = oldPipelineConfig.getMetadata().getNamespace();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldPipelineConfig, newPipelineConfig);
        } catch (IOException e) {
            logger.log(Level.WARNING, String.format("Unable to generate patch for PipelineConfig '%s/%s', reason: %s",
                    namespace, name, e.getMessage()), e);
            return;
        }

        // When use remove op on omitempty empty field, will cause 422 Exception
        List<JsonObject> bodyWithoutRemove = new LinkedList<>();
        List<JsonObject> bodyOnlyRemove = new LinkedList<>();

        JsonArray arr = new Gson().fromJson(patch, JsonArray.class);
        arr.forEach(jsonElement -> {
            JsonElement op = jsonElement.getAsJsonObject().get("op");
            if (op != null) {
                if ("remove".equals(op.getAsString())) {
                    bodyOnlyRemove.add(jsonElement.getAsJsonObject());
                } else {
                    bodyWithoutRemove.add(jsonElement.getAsJsonObject());
                }
            }
        });

        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        try {
            api.patchNamespacedPipelineConfig(
                    name,
                    namespace,
                    bodyWithoutRemove,
                    null,
                    null);
        } catch (ApiException e) {
            logger.log(Level.WARNING, String.format("Unable to patch PipelineConfig '%s/%s', reason: %s",
                    namespace, name, e.getMessage()), e);
        }
        try {
            api.patchNamespacedPipelineConfig(
                    name,
                    namespace,
                    bodyOnlyRemove,
                    null,
                    null);
        } catch (ApiException e) {
            logger.log(Level.WARNING, String.format("Unable to patch PipelineConfig '%s/%s', reason: %s",
                    namespace, name, e.getMessage()), e);
        }

    }

    // innerDeleteEventToJenkinsJob is the actual delete logic at the heart of
    // deleteEventToJenkinsJob
    // that is either in a sync block or not based on the presence of a BC uid
    private void innerDeleteEventToJenkinsJob(final V1alpha1PipelineConfig pipelineConfig) throws Exception {
        final TopLevelItem item = PipelineConfigToJobMap.getItemByPC(pipelineConfig);
        if (item != null) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            synchronized (pipelineConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() throws Exception {
                        final String pcId = pipelineConfig.getMetadata().getNamespace() + pipelineConfig.getMetadata().getName();
                        try {
                            deleteInProgress(pcId);
                            item.delete();
                        } finally {
                            PipelineConfigToJobMap.removeJobWithPipelineConfig(pipelineConfig);
                            Jenkins.getInstance().rebuildDependencyGraphAsync();
                            deleteCompleted(pcId);
                        }
                        return null;
                    }
                });
            }
        }
    }

    public static synchronized void deleteInProgress(String pcName) {
        deletesInProgress.add(pcName);
    }

    public static synchronized boolean isDeleteInProgress(String pcID) {
        return deletesInProgress.contains(pcID);
    }


    public static synchronized void deleteCompleted(String pcID) {
        deletesInProgress.remove(pcID);
    }


    public V1alpha1PipelineConfig getPipelineConfig(String namespace, String name) {
        Lister<V1alpha1PipelineConfig> lister = new Lister<>(pipelineConfigInformer.getIndexer());
        return lister.namespace(namespace).get(name);
    }

    public List<V1alpha1PipelineConfig> listPipelineConfigs(String namespace) {
        Lister<V1alpha1PipelineConfig> lister = new Lister<>(pipelineConfigInformer.getIndexer());
        return lister.namespace(namespace).list();
    }

    public static V1Status deletePipelineConfig(String namespace, String name) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        try {
            return api.deleteNamespacedPipelineConfig(name, namespace, new V1DeleteOptions(), null, null, null, null, null);
        } catch (ApiException e) {
            logger.log(Level.WARNING, String.format("Unable to delete pipelineconfig '%s/%s', reason: %s", namespace, name, e.getMessage()), e);
            return null;
        }
    }

    public static V1alpha1PipelineConfig createPipelineConfig(String namespace, V1alpha1PipelineConfig pc) throws ApiException {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        return api.createNamespacedPipelineConfig(namespace, pc, null, null, null);
    }
}
