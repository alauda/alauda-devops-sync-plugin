package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.extend.controller.ControllerManager;
import io.alauda.devops.java.client.extend.controller.builder.ControllerBuilder;
import io.alauda.devops.java.client.extend.controller.builder.ControllerManangerBuilder;
import io.alauda.devops.java.client.models.V1alpha1Jenkins;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.support.KubernetesCluster;
import io.alauda.jenkins.devops.support.KubernetesClusterConfiguration;
import io.alauda.jenkins.devops.support.KubernetesClusterConfigurationListener;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.informer.SharedInformerFactory;
import jenkins.model.identity.IdentityRootAction;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY;

@Extension
public class ResourceSyncManager implements KubernetesClusterConfigurationListener {
    private static final Logger logger = LoggerFactory.getLogger(ResourceSyncManager.class);

    private ControllerManager controllerManager;
    private ExecutorService controllerManagerThread;
    private String pluginStatus;
    private boolean started = false;

    @Override
    public synchronized void onConfigChange(KubernetesCluster cluster, ApiClient client) {
        started = false;
        shutdown(null);

        // check if jenkins valid
        String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
        if (!checkJenkinsService(jenkinsService)) {
            logger.warn("[ResourceSyncManager] The target Jenkins service {} is invalid, reason {}", jenkinsService, pluginStatus);
            return;
        }

        logger.debug("[ResourceSyncManager] Starting initialize controller manager");
        SharedInformerFactory informerFactory = new SharedInformerFactory();

        ExtensionList<ResourceSyncController> resourceSyncControllers = ResourceSyncController.all();
        logger.debug("[ResourceSyncManager] Found {} resourceSyncControllers", resourceSyncControllers.size());

        ControllerManangerBuilder controllerManagerBuilder =
                ControllerBuilder.controllerManagerBuilder(informerFactory);

        resourceSyncControllers.forEach(resourceSyncController -> resourceSyncController.add(controllerManagerBuilder, informerFactory));

        controllerManager = controllerManagerBuilder.build();

        pluginStatus = "";
        started = true;

        controllerManagerThread = Executors.newSingleThreadExecutor();
        controllerManagerThread.submit(() -> controllerManager.run());
    }

    @Override
    public synchronized void onConfigError(KubernetesCluster cluster, Throwable reason) {
        started = false;
        shutdown(reason);
    }

    public synchronized void shutdown(Throwable reason) {
        if (controllerManager != null) {
            controllerManager.shutdown();
            controllerManager = null;
        }

        if (controllerManagerThread != null && !controllerManagerThread.isShutdown()) {
            controllerManagerThread.shutdown();
        }

        if (reason == null) {
            logger.warn("[ResourceSyncManager] ResourceSyncManager is stopped, reason is null, seems stopped by user");
        } else {
            logger.warn("[ResourceSyncManager] ResourceSyncManager is stopped, reason: {}", reason);
        }
    }

    private boolean checkJenkinsService(String jenkinsService) {
        if (StringUtils.isEmpty(jenkinsService)) {
            pluginStatus = "[ResourceSyncManager] Plugin cannot get mapped Jenkins Service, jenkins service name in configuration is empty";
            return false;
        }

        V1alpha1Jenkins currentJenkins;
        try {
            currentJenkins = getJenkins(jenkinsService);
        } catch (ApiException e) {
            pluginStatus = String.format("[ResourceSyncManager] Plugin cannot get mapped Jenkins Service by name %s in devops-apiserver, reason %s, body %s", jenkinsService, e.getMessage(), e.getResponseBody());
            return false;
        }
        if (currentJenkins == null) {
            pluginStatus = String.format("[ResourceSyncManager] Plugin cannot to get mapped Jenkins Service by name %s in devops-apiserver, please ensure the jenkins service name is correct", jenkinsService);
            return false;
        }

        final String currentFingerprint = new IdentityRootAction().getFingerprint();
        Map<String, String> annotations = currentJenkins.getMetadata().getAnnotations();
        String fingerprint;
        if (annotations == null || (fingerprint = annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY)) == null) {
            V1alpha1Jenkins newJenkins = DeepCopyUtils.deepCopy(currentJenkins);

            newJenkins.getMetadata().getAnnotations().put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY, currentFingerprint);
            return updateJenkins(currentJenkins, newJenkins);
        } else if (!org.apache.commons.lang.StringUtils.equals(currentFingerprint, fingerprint)) {
            pluginStatus = String.format("[ResourceSyncManager] Fingerprint from target Jenkins service %s does not match with current Jenkins %s.", fingerprint, currentFingerprint);
            return false;
        }
        return true;
    }


    private V1alpha1Jenkins getJenkins(String name) throws ApiException {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        return api.readJenkins(name, null, null, null);
    }

    private boolean updateJenkins(V1alpha1Jenkins oldJenkins, V1alpha1Jenkins newJenkins) {
        String name = oldJenkins.getMetadata().getName();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldJenkins, newJenkins);
        } catch (IOException e) {
            pluginStatus = String.format("[ResourceSyncManager] Unable to generate patch for Jenkins '%s', reason: %s",
                    name, e.getMessage());
            return false;
        }
        List<JsonObject> body = new LinkedList<>();
        JsonArray arr = new Gson().fromJson(patch, JsonArray.class);
        arr.forEach(jsonElement -> body.add(jsonElement.getAsJsonObject()));

        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        try {
            api.patchJenkins(
                    name,
                    body,
                    null,
                    null);
        } catch (ApiException e) {
            pluginStatus = String.format("[ResourceSyncManager] Unable to patch Jenkins '%s', reason: %s",
                    name, e.getMessage());
            return false;
        }
        return true;
    }

    public String getPluginStatus() {
        return pluginStatus;
    }

    public boolean isStarted() {
        return started && Clients.allRegisteredResourcesSynced();
    }

    public synchronized void notifyJenkinsServiceChanged() {
        KubernetesCluster cluster = KubernetesClusterConfiguration.get().getCluster();
        this.onConfigChange(cluster, Configuration.getDefaultApiClient());
    }

    public static ResourceSyncManager getSyncManager() {
        return ExtensionList.lookup(ResourceSyncManager.class).get(0);
    }
}
