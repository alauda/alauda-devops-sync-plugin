package io.alauda.jenkins.devops.sync.controller;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.devops.java.client.extend.controller.Controller;
import io.alauda.devops.java.client.extend.controller.builder.ControllerBuilder;
import io.alauda.devops.java.client.extend.controller.builder.ControllerManangerBuilder;
import io.alauda.devops.java.client.extend.controller.reconciler.Reconciler;
import io.alauda.devops.java.client.extend.controller.reconciler.Request;
import io.alauda.devops.java.client.extend.controller.reconciler.Result;
import io.alauda.devops.java.client.extend.workqueue.DefaultRateLimitingQueue;
import io.alauda.devops.java.client.extend.workqueue.RateLimitingQueue;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.NamespaceClient;
import io.alauda.jenkins.devops.sync.controller.util.InformerUtils;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import jenkins.model.Jenkins;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Extension
public class NamespaceController implements ResourceSyncController {

    private static final Logger logger = LoggerFactory.getLogger(NamespaceController.class);
    private static final String CONTROLLER_NAME = "NamespaceController";

    @Override
    public void add(ControllerManangerBuilder managerBuilder, SharedInformerFactory factory) {
        CoreV1Api api = new CoreV1Api();

        SharedIndexInformer<V1Namespace> informer = InformerUtils.getExistingSharedIndexInformer(factory, V1Namespace.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(
                    callGeneratorParams -> {
                        try {
                            return api.listNamespaceCall(
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
                    }, V1Namespace.class, V1NamespaceList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
        }


        NamespaceClient client = new NamespaceClient(informer);
        Clients.register(V1Namespace.class, client);

        RateLimitingQueue<Request> rateLimitingQueue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadScheduledExecutor());

        Controller controller =
                ControllerBuilder.defaultBuilder(factory).watch(
                        ControllerBuilder.controllerWatchBuilder(V1Namespace.class)
                                .withWorkQueueKeyFunc(namespace ->
                                        new Request(namespace.getMetadata().getName()))
                                .withWorkQueue(rateLimitingQueue)
                                .build())
                        .withReconciler(new NamespaceReconciler(new Lister<>(informer.getIndexer())))
                        .withName(CONTROLLER_NAME)
                        .withReadyFunc(informer::hasSynced)
                        .withWorkerCount(1)
                        .withWorkQueue(rateLimitingQueue)
                        .build();

        managerBuilder.addController(controller);
    }


    static class NamespaceReconciler implements Reconciler {
        private Lister<V1Namespace> namespaceLister;

        public NamespaceReconciler(Lister<V1Namespace> namespaceLister) {
            this.namespaceLister = namespaceLister;
        }

        @Override
        public Result reconcile(Request request) {
            V1Namespace namespace = namespaceLister.get(request.getName());
            if (namespace != null) {
                return new Result(false);
            }

            String folderName = request.getName();
            try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
                Folder folder = Jenkins.getInstance().getItemByFullName(folderName, Folder.class);
                if (folder == null) {
                    logger.warn("[{}] Folder {} can't found.", CONTROLLER_NAME, folderName);
                    return new Result(false);
                }

                AlaudaFolderProperty alaudaFolderProperty = folder.getProperties().get(AlaudaFolderProperty.class);
                if (alaudaFolderProperty == null) {
                    logger.debug("[{}] Folder {} don't have AbstractFolderProperty, will skip it.", CONTROLLER_NAME, folderName);
                    return new Result(false);
                }

                int itemCount = folder.getItems().size();
                if (itemCount > 0) {
                    logger.debug("[{}] Will not delete folder {} that still has items, count {}.", CONTROLLER_NAME, folderName, itemCount);

                    alaudaFolderProperty.setDirty(true);
                    try {
                        folder.save();
                    } catch (IOException e) {
                        logger.warn("[{}] Unable to save folder {}, reason: {}", CONTROLLER_NAME, folderName, e);
                    }
                    return new Result(false);
                }

                try {
                    folder.delete();
                } catch (IOException e) {
                    logger.warn("[{}] Failed to delete folder {}, reason: {}", CONTROLLER_NAME, folderName, e);
                    return new Result(true);

                } catch (InterruptedException e) {
                    logger.warn("[{}] Failed to delete folder {}, reason: {}", CONTROLLER_NAME, folderName, e);
                    Thread.currentThread().interrupt();
                }

            } catch (Exception e) {
                logger.info("[{}] Unable to delete folder {}, reason {}", CONTROLLER_NAME, folderName, e.getMessage());
            }
            return new Result(false);
        }
    }

}


