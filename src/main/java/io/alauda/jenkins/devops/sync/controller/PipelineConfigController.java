package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.extend.workqueue.WorkQueue;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.controller.util.Wait;
import io.alauda.jenkins.devops.sync.exception.ConditionsUtils;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Status;
import org.apache.commons.lang.StringUtils;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;


@Extension
public class PipelineConfigController extends BaseController<V1alpha1PipelineConfig, V1alpha1PipelineConfigList> {
    private static final Logger logger = LoggerFactory.getLogger(PipelineConfigController.class);
    private SharedIndexInformer<V1alpha1PipelineConfig> pipelineConfigInformer;

    private volatile boolean dependentControllerSynced = false;
    private JenkinsClient jenkinsClient = JenkinsClient.getInstance();

    @Override
    public SharedIndexInformer<V1alpha1PipelineConfig> newInformer(ApiClient client, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        this.pipelineConfigInformer = factory.sharedIndexInformerFor(
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


        return pipelineConfigInformer;
    }

    @Override
    public EnqueueResourceEventHandler<V1alpha1PipelineConfig, NamespaceName> newHandler() {
        return new EnqueueResourceEventHandler<V1alpha1PipelineConfig, NamespaceName>() {
            @Override
            public void onAdd(V1alpha1PipelineConfig pipelineConfig, WorkQueue<NamespaceName> workQueue) {
                String pipelineConfigName = pipelineConfig.getMetadata().getName();
                String pipelineConfigNamespace = pipelineConfig.getMetadata().getNamespace();

                logger.debug("[{}] receives event: Add; PipelineConfig '{}/{}'",
                        getControllerName(), pipelineConfigNamespace, pipelineConfigName);

                workQueue.add(new NamespaceName(pipelineConfigNamespace, pipelineConfigName));
            }

            @Override
            public void onUpdate(V1alpha1PipelineConfig oldPipelineConfig, V1alpha1PipelineConfig newPipelineConfig, WorkQueue<NamespaceName> workQueue) {
                String pipelineConfigName = newPipelineConfig.getMetadata().getName();
                String pipelineConfigNamespace = newPipelineConfig.getMetadata().getNamespace();

                if (oldPipelineConfig.getMetadata().getResourceVersion().equals(newPipelineConfig.getMetadata().getResourceVersion())) {
                    logger.debug("[{}] ResourceVersion of PipelineConfig '{}/{}' is equal, will skip update event for it", getControllerName(), pipelineConfigNamespace, pipelineConfigName);
                    return;
                }


                logger.debug("[{}] receives event: Update; PipelineConfig '{}/{}'",
                        getControllerName(),
                        pipelineConfigNamespace, pipelineConfigName);

                workQueue.add(new NamespaceName(pipelineConfigNamespace, pipelineConfigName));
            }

            @Override
            public void onDelete(V1alpha1PipelineConfig pipelineConfig, boolean deletedFinalStateUnknown, WorkQueue<NamespaceName> workQueue) {
                String pipelineConfigName = pipelineConfig.getMetadata().getName();
                String pipelineConfigNamespace = pipelineConfig.getMetadata().getNamespace();

                logger.debug("[{}] receives event: Delete; PipelineConfig '{}/{}'", getControllerName(),
                        pipelineConfigNamespace, pipelineConfigName);

                workQueue.add(new NamespaceName(pipelineConfigNamespace, pipelineConfigName));
            }
        };
    }

    @Override
    public void waitControllerReady() throws Exception {
        try {
            JenkinsBindingController jenkinsBindingController = JenkinsBindingController.getCurrentJenkinsBindingController();
            if (jenkinsBindingController == null) {
                logger.error("[{}] Unable to start PipelineConfigController, JenkinsBindingController must be initialized first", getControllerName());
                return;
            }
            CodeRepositoryController codeRepositoryController = CodeRepositoryController.getCurrentCodeRepositoryController();
            if (codeRepositoryController == null) {
                logger.error("[{}] Unable to start PipelineConfigController, CodeRepository must be initialized first", getControllerName());
                return;
            }

            jenkinsBindingController.waitUntilJenkinsBindingControllerSyncedAndValid(1000 * 60);
            codeRepositoryController.waitUntilCodeRepositoryControllerSynced(1000 * 60);
            dependentControllerSynced = true;
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            logger.error("[{}] Unable to start PipelineConfigController, reason %s", getControllerName(), e.getMessage());
            throw e;
        }
    }

    @Override
    public ReconcileResult reconcile(NamespaceName namespaceName) throws Exception {
        String namespace = namespaceName.getNamespace();
        String name = namespaceName.getName();
        ReconcileResult result = new ReconcileResult(false);

        V1alpha1PipelineConfig pc = getPipelineConfig(namespace, name);
        if (pc == null) {
            logger.debug("[{}] Cannot found PipelineConfig '{}/{}' in local lister, will try to remove it's correspondent Jenkins job", getControllerName(), namespace, name);
            boolean deleteSucceed;
            try {
                deleteSucceed = jenkinsClient.deleteJob(namespaceName);
                if (!deleteSucceed) {
                    logger.warn("[{}] Failed to delete job for PipelineConfig '{}/{}'", getControllerName(), namespace, name);
                }
            } catch (IOException | InterruptedException e) {
                logger.warn("[{}] Failed to delete job for PipelineConfig '{}/{}', reason {}", getControllerName(), namespace, name, e.getMessage());
            }
            return result;
        }

        if (!JenkinsBindingController.isBindResource(pc.getSpec().getJenkinsBinding().getName())) {
            logger.debug("[{}] PipelineConfigController: {}/{}' is not bind to correct jenkinsbinding, will skip it", getControllerName(), namespace, name);
            return result;
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
                    return result;
                }

                jenkinsClient.upsertJob(pipelineConfigCopy);
            } catch (PipelineConfigConvertException e) {
                logger.warn("[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason %s", getControllerName(), namespace, name, StringUtils.join(e.getCauses(), " or "));
                conditions.addAll(ConditionsUtils.convertToConditions(e.getCauses()));
            } catch (IOException e) {
                logger.warn("[{}] Failed to convert PipelineConfig '{}/{}' to Jenkins Job, reason %s", getControllerName(), namespace, name, e.getMessage());
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
            boolean succeed = updatePipelineConfig(pc, pipelineConfigCopy);
            return result.setRequeue(!succeed);
        }
    }

    @Override
    public String getControllerName() {
        return "PipelineConfigController";
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
            logger.warn("There are more than two PipelineConfigController exist, maybe a potential bug");
        }

        return pipelineConfigControllers.get(0);
    }


    public static boolean updatePipelineConfig(V1alpha1PipelineConfig oldPipelineConfig, V1alpha1PipelineConfig newPipelineConfig) {
        String name = oldPipelineConfig.getMetadata().getName();
        String namespace = oldPipelineConfig.getMetadata().getNamespace();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldPipelineConfig, newPipelineConfig);
        } catch (IOException e) {
            logger.warn("Unable to generate patch for PipelineConfig '{}/{}', reason: {}",
                    namespace, name, e.getMessage());
            return false;
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
            logger.warn(String.format("Unable to patch PipelineConfig '%s/%s', reason: %s, body: %s",
                    namespace, name, e.getMessage(), e.getResponseBody()), e);
            return false;
        }
        try {
            api.patchNamespacedPipelineConfig(
                    name,
                    namespace,
                    bodyOnlyRemove,
                    null,
                    null);
        } catch (ApiException e) {
            logger.warn(String.format("Unable to patch PipelineConfig '%s/%s', reason: %s, body: %s",
                    namespace, name, e.getMessage(), e.getResponseBody()), e);
            return false;
        }
        return true;
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
            logger.warn(String.format("Unable to delete pipelineconfig '%s/%s', reason: %s", namespace, name, e.getMessage()), e);
            return null;
        }
    }

    public static V1alpha1PipelineConfig createPipelineConfig(String namespace, V1alpha1PipelineConfig pc) throws ApiException {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        return api.createNamespacedPipelineConfig(namespace, pc, null, null, null);
    }
}
