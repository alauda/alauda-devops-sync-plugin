package io.alauda.jenkins.devops.sync.controller;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY;

import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Jenkins;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.support.KubernetesCluster;
import io.alauda.jenkins.devops.support.KubernetesClusterConfiguration;
import io.alauda.jenkins.devops.support.KubernetesClusterConfigurationListener;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.extended.controller.ControllerManager;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.wait.Wait;
import io.kubernetes.client.informer.SharedInformerFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import jenkins.model.identity.IdentityRootAction;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class ResourceControllerManager implements KubernetesClusterConfigurationListener {
  private static final Logger logger = LoggerFactory.getLogger(ResourceControllerManager.class);

  private ControllerManager controllerManager;
  private ExecutorService controllerManagerThread;
  private String pluginStatus;
  private AtomicBoolean started = new AtomicBoolean(false);

  @Override
  public synchronized void onConfigChange(KubernetesCluster cluster, ApiClient client) {
    started.set(false);
    shutdown(null);

    controllerManagerThread = Executors.newSingleThreadExecutor();
    controllerManagerThread.submit(
        () -> {
          Wait.poll(
              Duration.ofMinutes(1),
              Duration.ofDays(1),
              () -> {
                boolean isEnabled = AlaudaSyncGlobalConfiguration.get().isEnabled();
                if (!isEnabled) {
                  logger.warn(
                      "[ResourceControllerManager] Alauda DevOps Sync plugin is disabled, won't start controllers");
                  return false;
                }

                String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
                if (!checkJenkinsService(jenkinsService)) {
                  logger.warn(
                      "[ResourceControllerManager] The target Jenkins service {} is invalid, reason {}",
                      jenkinsService,
                      pluginStatus);
                  return false;
                }
                return true;
              });

          logger.warn("[ResourceControllerManager] Starting initialize controller manager");
          SharedInformerFactory informerFactory = new SharedInformerFactory();

          ExtensionList<ResourceController> resourceControllers = ResourceController.all();
          logger.warn(
              "[ResourceControllerManager] Found {} resource controllers",
              resourceControllers.size());

          ControllerManagerBuilder controllerManagerBuilder =
              ControllerBuilder.controllerManagerBuilder(informerFactory);

          resourceControllers.forEach(
              resourceSyncController ->
                  resourceSyncController.add(controllerManagerBuilder, informerFactory));

          controllerManager = controllerManagerBuilder.build();

          pluginStatus = "";
          started.set(true);

          controllerManager.run();
        });
  }

  @Override
  public synchronized void onConfigError(KubernetesCluster cluster, Throwable reason) {
    started.set(false);
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
      logger.warn(
          "[ResourceControllerManager] ResourceControllerManager is stopped, reason is null, seems stopped by user");
    } else {
      logger.warn(
          "[ResourceControllerManager] ResourceControllerManager is stopped, reason: {}",
          reason.getMessage());
    }
  }

  private boolean checkJenkinsService(String jenkinsService) {
    if (StringUtils.isEmpty(jenkinsService)) {
      pluginStatus =
          "[ResourceControllerManager] Plugin cannot get mapped Jenkins Service, jenkins service name in configuration is empty";
      return false;
    }

    V1alpha1Jenkins currentJenkins;
    try {
      currentJenkins = getJenkins(jenkinsService);
    } catch (ApiException e) {
      pluginStatus =
          String.format(
              "[ResourceControllerManager] Plugin cannot get mapped Jenkins Service by name %s in devops-apiserver, reason %s, body %s",
              jenkinsService, e.getMessage(), e.getResponseBody());
      return false;
    }
    if (currentJenkins == null) {
      pluginStatus =
          String.format(
              "[ResourceControllerManager] Plugin cannot to get mapped Jenkins Service by name %s in devops-apiserver, please ensure the jenkins service name is correct",
              jenkinsService);
      return false;
    }

    final String currentFingerprint = new IdentityRootAction().getFingerprint();
    Map<String, String> annotations = currentJenkins.getMetadata().getAnnotations();
    String fingerprint;
    if (annotations == null
        || (fingerprint = annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY)) == null) {
      V1alpha1Jenkins newJenkins = DeepCopyUtils.deepCopy(currentJenkins);

      newJenkins
          .getMetadata()
          .getAnnotations()
          .put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY, currentFingerprint);
      if (!JenkinsClient.getInstance().updateJenkins(currentJenkins, newJenkins)) {
        pluginStatus =
            String.format(
                "[ResourceControllerManager] Unable to generate patch for Jenkins '%s', reason: %s",
                jenkinsService, "Patch failed");
        return false;
      }
    } else if (!org.apache.commons.lang.StringUtils.equals(currentFingerprint, fingerprint)) {
      pluginStatus =
          String.format(
              "[ResourceControllerManager] Fingerprint from target Jenkins service %s does not match with current Jenkins %s.",
              fingerprint, currentFingerprint);
      return false;
    }
    return true;
  }

  private V1alpha1Jenkins getJenkins(String name) throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    return api.readJenkins(name, null, null, null);
  }

  public String getPluginStatus() {
    return pluginStatus;
  }

  public boolean isStarted() {
    return started.get() && Clients.allRegisteredResourcesSynced();
  }

  public synchronized void restart() {
    KubernetesCluster cluster = KubernetesClusterConfiguration.get().getCluster();
    this.onConfigChange(cluster, Configuration.getDefaultApiClient());
  }

  public static ResourceControllerManager getControllerManager() {
    return ExtensionList.lookup(ResourceControllerManager.class).get(0);
  }
}
