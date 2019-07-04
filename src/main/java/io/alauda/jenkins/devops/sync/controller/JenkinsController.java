package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Jenkins;
import io.alauda.devops.java.client.models.V1alpha1JenkinsList;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.support.KubernetesCluster;
import io.alauda.jenkins.devops.support.KubernetesClusterConfiguration;
import io.alauda.jenkins.devops.support.controller.Controller;
import io.alauda.jenkins.devops.support.controller.ControllerManager;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.controller.util.Wait;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import jenkins.model.identity.IdentityRootAction;
import org.parboiled.common.StringUtils;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY;

@Extension
public class JenkinsController implements Controller<V1alpha1Jenkins, V1alpha1JenkinsList> {
    private static final Logger logger = Logger.getLogger(JenkinsController.class.getName());

    private SharedIndexInformer<V1alpha1Jenkins> jenkinsInformer;
    private volatile boolean started = false;
    private String controllerStatus = "";

    @Override
    public void initialize(ApiClient apiClient, SharedInformerFactory sharedInformerFactory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        controllerStatus = "Initializing JenkinsController";
        jenkinsInformer = sharedInformerFactory.sharedIndexInformerFor(
                callGeneratorParams -> {
                    try {
                        return api.listJenkinsCall(
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
                }, V1alpha1Jenkins.class, V1alpha1JenkinsList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
    }

    @Override
    public void start() {
        started = true;
    }

    @Override
    public void shutDown(Throwable throwable) {
        controllerStatus = "JenkinsController is stopped";
        if (jenkinsInformer == null) {
            return;
        }

        try {
            jenkinsInformer.stop();
            jenkinsInformer = null;
        } catch (Throwable e) {
            logger.log(Level.WARNING, String.format("Unable to stop JenkinsController, reason: %s", e.getMessage()));
        }
    }

    @Override
    public boolean hasSynced() {
        return jenkinsInformer != null && jenkinsInformer.hasSynced();
    }


    public boolean isValid() {
        return hasSynced() && isValidJenkinsInstance();
    }

    @Override
    public Type getType() {
        return new TypeToken<V1alpha1Jenkins>() {
        }.getType();
    }

    public boolean isValidJenkinsInstance() {
        String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
        if (StringUtils.isEmpty(jenkinsService)) {
            controllerStatus = "JenkinsController cannot get mapped Jenkins Service, jenkins service name is empty";
            return false;
        }

        Lister<V1alpha1Jenkins> lister = new Lister<>(jenkinsInformer.getIndexer());
        V1alpha1Jenkins currentJenkins = lister.get(jenkinsService);
        if (currentJenkins == null) {
            controllerStatus = "JenkinsController cannot to get mapped Jenkins Service, please ensure jenkins service name is correct";
            return false;
        }

        final String currentFingerprint = new IdentityRootAction().getFingerprint();
        Map<String, String> annotations = currentJenkins.getMetadata().getAnnotations();
        String fingerprint;
        if (annotations == null || (fingerprint = annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY)) == null) {
            V1alpha1Jenkins newJenkins = DeepCopyUtils.deepCopy(currentJenkins);

            newJenkins.getMetadata().getAnnotations().put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY, currentFingerprint);
            updateJenkins(currentJenkins, newJenkins);
        } else if (!org.apache.commons.lang.StringUtils.equals(currentFingerprint, fingerprint)) {
            controllerStatus = String.format("Fingerprint from target Jenkins service %s does not match with current Jenkins %s.", fingerprint, currentFingerprint);
            logger.log(Level.WARNING, controllerStatus);
            return false;
        }
        return true;
    }

    public void updateJenkins(V1alpha1Jenkins oldJenkins, V1alpha1Jenkins newJenkins) {
        String name = oldJenkins.getMetadata().getName();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldJenkins, newJenkins);
        } catch (IOException e) {
            logger.log(Level.WARNING, String.format("Unable to generate patch for Jenkins '%s', reason: %s",
                    name, e.getMessage()), e);
            return;
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
            logger.log(Level.WARNING, String.format("Unable to patch Jenkins '%s', reason: %s",
                    name, e.getMessage()), e);
        }
    }

    /**
     * Wait until CodeRepositoryController synced
     *
     * @param timeout milliseconds
     */
    public void waitUntilJenkinsControllerSyncedAndValid(long timeout) throws InterruptedException, ExecutionException, TimeoutException {
        if (isValid()) {
            return;
        }
        Wait.waitUntil(this, JenkinsController::hasSynced, 500, timeout, TimeUnit.MILLISECONDS);
    }

    /**
     * Get current running JenkinsBindingController
     *
     * @return null if there is no active JenkinsBindingController
     */
    public static JenkinsController getCurrentJenkinsController() {
        ExtensionList<JenkinsController> jenkinsControllers = ExtensionList.lookup(JenkinsController.class);

        if (jenkinsControllers.size() > 1) {
            logger.log(Level.WARNING, "There are more than two JenkinsControllers exist, maybe a potential bug");
        }

        return jenkinsControllers.get(0);
    }

    public synchronized void notifyJenkinsServiceChanged() {
        if (started) {
            started = false;
            ExtensionList<ControllerManager> controllerManagers = ExtensionList.lookup(ControllerManager.class);
            if (controllerManagers.size() == 0) {
                logger.log(Level.SEVERE, "Failed to restart controllers to reapply jenkin service, reason: Unable to find ControllerManager");
            }

            KubernetesCluster cluster = KubernetesClusterConfiguration.get().getCluster();
            // trigger event to restart all controllers
            // TODO This implementation will restart all controllers, maybe need to improve in the future
            controllerManagers.get(0).onConfigChange(cluster, Configuration.getDefaultApiClient());
        }
    }

    public String getControllerStatus() {
        return controllerStatus;
    }
}
