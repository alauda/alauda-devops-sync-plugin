package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBindingList;
import io.alauda.jenkins.devops.support.controller.Controller;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.controller.util.Wait;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;

import javax.annotation.Nonnull;
import java.lang.reflect.Type;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

@Extension
public class JenkinsBindingController implements Controller<V1alpha1JenkinsBinding, V1alpha1JenkinsBindingList> {
    private static final Logger logger = Logger.getLogger(JenkinsBindingController.class.getName());

    private SharedIndexInformer<V1alpha1JenkinsBinding> jenkinsBindingInformer;
    private volatile boolean dependentControllerSynced = false;

    @Override
    public void initialize(ApiClient apiClient, SharedInformerFactory sharedInformerFactory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        jenkinsBindingInformer = sharedInformerFactory.sharedIndexInformerFor(
                callGeneratorParams -> {
                    try {
                        return api.listJenkinsBindingForAllNamespacesCall(
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
                }, V1alpha1JenkinsBinding.class, V1alpha1JenkinsBindingList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
    }

    @Override
    public void start() {
        JenkinsController jenkinsController = JenkinsController.getCurrentJenkinsController();
        if (jenkinsController == null) {
            logger.log(Level.SEVERE, "Unable to start JenkinsBindingController, JenkinsController must be initialized first");
            return;
        }

        try {
            jenkinsController.waitUntilJenkinsControllerSyncedAndValid(1000 * 60 * 60);
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, String.format("Unable to start JenkinsController, reason %s", e.getMessage()), e);
            return;
        }
        catch (InterruptedException e) {
            logger.log(Level.SEVERE, String.format("Unable to start JenkinsController, reason %s", e.getMessage()), e);
            Thread.currentThread().interrupt();
            return;
        }

        dependentControllerSynced = true;
    }

    @Override
    public void shutDown(Throwable throwable) {
        if (jenkinsBindingInformer == null) {
            return;
        }

        try {
            jenkinsBindingInformer.stop();
            jenkinsBindingInformer = null;
        } catch (Throwable e) {
            logger.log(Level.WARNING, String.format("Unable to stop JenkinsBindingController, reason: %s", e.getMessage()));
        }
    }

    @Override
    public boolean hasSynced() {
        return jenkinsBindingInformer != null && jenkinsBindingInformer.hasSynced();
    }

    public boolean isValid() {
        return hasSynced() && dependentControllerSynced;
    }

    @Override
    public Type getType() {
        return new TypeToken<V1alpha1JenkinsBinding>() {
        }.getType();
    }

    @Nonnull
    public List<V1alpha1JenkinsBinding> getJenkinsBindings() {
        String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();

        if (jenkinsBindingInformer == null) {
            logger.log(Level.SEVERE, "JenkinsBinding controller may stopped, unable to get jenkinsbinding list");
            return Collections.emptyList();
        }

//        try {
//            if (InformerUtils.isStopped(jenkinsBindingInformer, this.getClass())) {
//                return null;
//            }
//        } catch (IllegalAccessException e) {
//            logger.log(Level.SEVERE, String.format("Unable to know whether JenkinsBindingController stopped, reason %s", e.getMessage()), e);
//            return null;
//        }

        if (!hasSynced()) {
            logger.log(Level.FINE, "JenkinsBindingController has not synced now, won't be able to get jenkinsbinding list");
            return Collections.emptyList();
        }

        return jenkinsBindingInformer.getIndexer()
                .list()
                .stream()
                .filter(jenkinsBinding -> jenkinsBinding.getSpec().getJenkins().getName().equals(jenkinsService))
                .collect(Collectors.toList());
    }

    /**
     * Wait until JenkinsBindingController synced
     *
     * @param timeout milliseconds
     */
    public void waitUntilJenkinsBindingControllerSyncedAndValid(long timeout) throws InterruptedException, ExecutionException, TimeoutException {
        if (hasSynced()) {
            return;
        }
        Wait.waitUntil(this, JenkinsBindingController::isValid, 500, timeout, TimeUnit.MILLISECONDS);
    }

    @Nonnull
    public List<String> getBindingNamespaces() {
        List<V1alpha1JenkinsBinding> jenkinsBindings = getJenkinsBindings();

        return jenkinsBindings.stream()
                .map(jenkinsBinding -> jenkinsBinding.getMetadata().getNamespace())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * Get current running JenkinsBindingController
     *
     * @return null if there is no active JenkinsBindingController
     */
    public static JenkinsBindingController getCurrentJenkinsBindingController() {
        ExtensionList<JenkinsBindingController> jenkinsBindingControllers = ExtensionList.lookup(JenkinsBindingController.class);

        if (jenkinsBindingControllers.size() > 1) {
            logger.log(Level.WARNING, "There are more than two JenkinsBindingControllers exist, maybe a potential bug");
        }

        return jenkinsBindingControllers.get(0);
    }

    public static boolean isBindResource(String bindingName) {
        return getCurrentJenkinsBindingController()
                .getJenkinsBindings()
                .stream()
                .anyMatch(binding -> binding.getMetadata().getName().equals(bindingName));
    }
}
