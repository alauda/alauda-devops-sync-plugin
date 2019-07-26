package io.alauda.jenkins.devops.sync.controller;

import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.ExtensionList;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1CodeRepository;
import io.alauda.devops.java.client.models.V1alpha1CodeRepositoryList;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.controller.util.Wait;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;

import java.lang.reflect.Type;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class CodeRepositoryController extends BaseController<V1alpha1CodeRepository, V1alpha1CodeRepositoryList> {
    private static final Logger logger = Logger.getLogger(CodeRepositoryController.class.getName());

    private SharedIndexInformer<V1alpha1CodeRepository> codeRepositoryInformer;

    @Override
    public SharedIndexInformer<V1alpha1CodeRepository> newInformer(ApiClient client, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        codeRepositoryInformer = factory.sharedIndexInformerFor(
                callGeneratorParams -> {
                    try {
                        return api.listCodeRepositoryForAllNamespacesCall(
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
                }, V1alpha1CodeRepository.class, V1alpha1CodeRepositoryList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));

        return codeRepositoryInformer;
    }

    @Override
    public String getControllerName() {
        return "CodeRepositoryController";
    }

    @Override
    public boolean hasSynced() {
        return codeRepositoryInformer != null && codeRepositoryInformer.hasSynced();
    }

    @Override
    public Type getType() {
        return new TypeToken<V1alpha1CodeRepository>(){}.getType();
    }

    /**
     * Wait until CodeRepositoryController synced
     *
     * @param timeout milliseconds
     */
    public void waitUntilCodeRepositoryControllerSynced(long timeout) throws InterruptedException, ExecutionException, TimeoutException {
        if (hasSynced()) {
            return;
        }
        Wait.waitUntil(this, CodeRepositoryController::hasSynced, 500, timeout, TimeUnit.MILLISECONDS);
    }


    public V1alpha1CodeRepository getCodeRepository(String namespace, String name) {
        Lister<V1alpha1CodeRepository> lister = new Lister<>(codeRepositoryInformer.getIndexer());
        V1alpha1CodeRepository repository = lister.namespace(namespace).get(name);
        if (repository == null) {
            logger.log(Level.FINE, String.format("Unable to find CodeRepository '%s/%s' from local lister, will try to retrieve it from server", namespace, name));
            try {
                repository = CodeRepositoryController.getRepositoryFromServer(namespace, name);
            } catch (ApiException e) {
                logger.log(Level.FINE, String.format("Unable to find CodeRepository '%s/%s' from server, reason %s", namespace, name, e.getMessage()));
            }
        }
        return repository;
    }

    /**
     * Get current running JenkinsBindingController
     *
     * @return null if there is no active JenkinsBindingController
     */
    public static CodeRepositoryController getCurrentCodeRepositoryController() {
        ExtensionList<CodeRepositoryController> codeRepositoryControllers = ExtensionList.lookup(CodeRepositoryController.class);
        if (codeRepositoryControllers.size() > 1) {
            logger.log(Level.WARNING, "There are more than two CodeRepositoryController exist, maybe a potential bug");
        }

        return codeRepositoryControllers.get(0);
    }

    //TODO move to client class for each resource type
    public static V1alpha1CodeRepository getRepositoryFromServer(String namespace, String name) throws ApiException {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        return api.readNamespacedCodeRepository(name, namespace, null, null, null);
    }
}
