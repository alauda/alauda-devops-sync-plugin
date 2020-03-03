package io.alauda.jenkins.devops.sync.controller;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_BASEDOMAIN;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_USED_BASEDOMAIN;

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
import io.alauda.jenkins.devops.sync.monitor.Metrics;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.Configuration;
import io.kubernetes.client.extended.controller.ControllerManager;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.informer.SharedInformerFactory;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
  private String baseDomain;
  private AtomicBoolean started = new AtomicBoolean(false);

  @Override
  public synchronized void onConfigChange(KubernetesCluster cluster, ApiClient client) {
    shutdown(null);

    controllerManagerThread = Executors.newSingleThreadExecutor();
    controllerManagerThread.submit(
        () -> {
          waitForJenkinsSetup();
          logger.info("[ResourceControllerManager] Starting initialize controller manager");

          SharedInformerFactory informerFactory = new SharedInformerFactory();

          ExtensionList<ResourceController> resourceControllers = ResourceController.all();
          logger.warn(
              "[ResourceControllerManager] Found {} resource controllers",
              resourceControllers.size());

          ControllerManagerBuilder controllerManagerBuilder =
              ControllerBuilder.controllerManagerBuilder(informerFactory);

          resourceControllers.forEach(
              resourceSyncController -> {
                resourceSyncController.add(controllerManagerBuilder, informerFactory);
              });

          controllerManager = controllerManagerBuilder.build();

          logger.info(
              "[ResourceControllerManager] ControllerManager initialized, waiting for informers sync");
          informerFactory.startAllRegisteredInformers();
          if (!waitForInformersSync()) {
            logger.warn(
                "[ResourceControllerManager] Timeout to wait for informers sync, will restart controllerManager");
            this.restart();
            return;
          }

          pluginStatus = "";
          started.set(true);
          Metrics.syncManagerUpGauge.set(1);

          controllerManager.run();
        });
  }

  @Override
  public synchronized void onConfigError(KubernetesCluster cluster, Throwable reason) {
    shutdown(reason);
  }

  public synchronized void shutdown(Throwable reason) {
    started.set(false);
    Metrics.syncManagerUpGauge.set(0);

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

  private boolean waitForInformersSync() {
    return pollWithNoInitialDelay(
        Duration.ofSeconds(5),
        // if informers didn't sync after 30 minutes, we should stop to recheck it as there must
        // some network or configuration problems.
        Duration.ofMinutes(30),
        Clients::allRegisteredResourcesSynced);
  }

  private void waitForJenkinsSetup() {
    pollWithNoInitialDelay(
        Duration.ofMinutes(1),
        // we cannot set a infinite duration here, so we set it to one year, this should be long
        // enough
        Duration.ofDays(365),
        () -> {
          boolean isEnabled = AlaudaSyncGlobalConfiguration.get().isEnabled();
          if (!isEnabled) {
            pluginStatus =
                "Alauda DevOps Sync plugin has been disabled, will not start to sync with devops-apiserver";
            logger.warn(
                "[ResourceControllerManager] Alauda DevOps Sync plugin has been disabled, will not start to sync with devops-apiserver");
            return false;
          }

          return checkJenkinsService();
        });
  }

  private boolean checkJenkinsService() {
    String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();

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

    String basedomain =
        currentJenkins.getMetadata().getAnnotations().get(ALAUDA_DEVOPS_ANNOTATIONS_BASEDOMAIN);

    if (basedomain != null && !basedomain.equals("")) {
      baseDomain = basedomain;
      logger.warn(
          "[ResourceControllerManager] Alauda DevOps Sync plugin use basedomain {} from jenkins service",
          baseDomain);
    } else {
      baseDomain = ALAUDA_DEVOPS_USED_BASEDOMAIN;
      logger.warn(
          "[ResourceControllerManager] Alauda DevOps Sync plugin use default basedomain: {}",
          baseDomain);
    }

    String fingerprint;
    if (annotations == null
        || (fingerprint =
                annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY.get().toString()))
            == null) {
      V1alpha1Jenkins newJenkins = DeepCopyUtils.deepCopy(currentJenkins);

      newJenkins
          .getMetadata()
          .getAnnotations()
          .put(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY.get().toString(), currentFingerprint);
      if (!JenkinsClient.getInstance().updateJenkins(currentJenkins, newJenkins)) {
        pluginStatus =
            String.format(
                "[ResourceControllerManager] Unable to generate patch for Jenkins '%s', reason: %s",
                jenkinsService, "Patch failed");
        return false;
      }
    } else if (!StringUtils.equals(currentFingerprint, fingerprint)) {
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

  // TODO: should throw Expection or something can warn us when baseDomain is wrong
  public Supplier<String> getFormattedAnnotation(String annotation) {
    return new Supplier<String>() {
      @Override
      public String get() {
        return String.format("%s/%s", baseDomain, annotation);
      }
    };
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

  /**
   * Will check condition immediately, if failed then start polling in interval
   *
   * @param interval the interval period
   * @param timeout the timeout period
   * @param condition condition func which polling will check
   * @return
   */
  private boolean pollWithNoInitialDelay(
      Duration interval, Duration timeout, Supplier<Boolean> condition) {
    ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor();
    AtomicBoolean result = new AtomicBoolean(false);
    long dueDate = System.currentTimeMillis() + timeout.toMillis();
    ScheduledFuture<?> future =
        executorService.scheduleAtFixedRate(
            () -> {
              try {
                result.set(condition.get());
              } catch (Exception e) {
                result.set(false);
              }
            },
            Duration.ZERO.toMillis(),
            interval.toMillis(),
            TimeUnit.MILLISECONDS);
    try {
      while (System.currentTimeMillis() < dueDate) {
        if (result.get()) {
          future.cancel(true);
          return true;
        }
      }
    } catch (Exception e) {
      return result.get();
    }
    future.cancel(true);
    return result.get();
  }
}
