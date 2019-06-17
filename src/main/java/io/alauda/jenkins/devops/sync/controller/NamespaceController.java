package io.alauda.jenkins.devops.sync.controller;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.security.ACL;
import io.alauda.jenkins.devops.support.controller.Controller;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class NamespaceController implements Controller<V1Namespace, V1NamespaceList> {
    private static final Logger logger = Logger.getLogger(NamespaceController.class.getName());

    private SharedIndexInformer<V1Namespace> namespaceInformer;


    @Override
    public void initialize(ApiClient apiClient, SharedInformerFactory sharedInformerFactory) {
        CoreV1Api api = new CoreV1Api();

        namespaceInformer = sharedInformerFactory.sharedIndexInformerFor(
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
                }, V1Namespace.class, V1NamespaceList.class);

        namespaceInformer.addEventHandler(new ResourceEventHandler<V1Namespace>() {
            @Override
            public void onAdd(V1Namespace obj) {
            }

            @Override
            public void onUpdate(V1Namespace oldObj, V1Namespace newObj) {
            }

            @Override
            public void onDelete(V1Namespace namespace, boolean deletedFinalStateUnknown) {

                String folderName = namespace.getMetadata().getName();

                try {
                    ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                        @Override
                        public Void call() {
                            Folder folder = Jenkins.getInstance().getItemByFullName(folderName, Folder.class);
                            if (folder == null) {
                                logger.warning(String.format("Folder [%s] can't found.", folderName));
                                return null;
                            }

                            AlaudaFolderProperty alaudaFolderProperty = folder.getProperties().get(AlaudaFolderProperty.class);
                            if (alaudaFolderProperty == null) {
                                logger.log(Level.FINE, String.format("Folder [%s] don't have AbstractFolderProperty, will skip it.", folderName));
                                return null;
                            }

                            int itemCount = folder.getItems().size();
                            if (itemCount > 0) {
                                logger.log(Level.INFO, String.format("Will not delete folder [%s] that still has items, count %s.", folderName, itemCount));

                                alaudaFolderProperty.setDirty(true);
                                try {
                                    folder.save();
                                } catch (IOException e) {
                                    logger.log(Level.WARNING, String.format("Unable to save folder [%s]", folderName), e);
                                }
                                return null;
                            }

                            try {
                                folder.delete();
                            } catch (InterruptedException | IOException e) {
                                logger.log(Level.WARNING, String.format("Failed to delete folder [%s]", folderName, e));
                            }
                            return null;
                        }
                    });
                } catch (Exception ignore) {
                }
            }
        });
    }

    @Override
    public void start() {

    }

    @Override
    public void shutDown(Throwable throwable) {
        if (namespaceInformer == null) {
            return;
        }

        try {
            namespaceInformer.stop();
            namespaceInformer = null;
        } catch (Throwable e) {
            logger.log(Level.WARNING, String.format("Unable to stop NamespaceInformer, reason: %s", e.getMessage()));
        }
    }

    @Override
    public boolean hasSynced() {
        return namespaceInformer != null && namespaceInformer.hasSynced();
    }

    @Override
    public Type getType() {
        return new TypeToken<V1Namespace>() {
        }.getType();
    }
}
