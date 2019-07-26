package io.alauda.jenkins.devops.sync.controller;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.google.gson.reflect.TypeToken;
import hudson.Extension;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.devops.java.client.extend.workqueue.WorkQueue;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1NamespaceList;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.lang.reflect.Type;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class NamespaceController extends BaseController<V1Namespace, V1NamespaceList> {
    private static final Logger logger = Logger.getLogger(NamespaceController.class.getName());

    private SharedIndexInformer<V1Namespace> namespaceInformer;

    @Override
    public SharedIndexInformer<V1Namespace> newInformer(ApiClient client, SharedInformerFactory factory) {
        CoreV1Api api = new CoreV1Api();
        namespaceInformer = factory.sharedIndexInformerFor(
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

        return namespaceInformer;
    }

    @Override
    public EnqueueResourceEventHandler<V1Namespace, NamespaceName> newHandler() {
        return new EnqueueResourceEventHandler<V1Namespace, NamespaceName>() {
            @Override
            public void onAdd(V1Namespace obj, WorkQueue<NamespaceName> queue) {
            }

            @Override
            public void onUpdate(V1Namespace oldObj, V1Namespace newObj, WorkQueue<NamespaceName> queue) {
            }

            @Override
            public void onDelete(V1Namespace obj, boolean deletedFinalStateUnknown, WorkQueue<NamespaceName> queue) {
                queue.add(new NamespaceName("", obj.getMetadata().getName()));
            }
        };
    }

    @Override
    public ReconcileResult reconcile(NamespaceName namespaceName) throws Exception {
        String folderName = namespaceName.getName();
        ReconcileResult result = new ReconcileResult(false);

        try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
            Folder folder = Jenkins.getInstance().getItemByFullName(folderName, Folder.class);
            if (folder == null) {
                logger.warning(String.format("Folder [%s] can't found.", folderName));
                return result;
            }

            AlaudaFolderProperty alaudaFolderProperty = folder.getProperties().get(AlaudaFolderProperty.class);
            if (alaudaFolderProperty == null) {
                logger.log(Level.FINE, String.format("Folder [%s] don't have AbstractFolderProperty, will skip it.", folderName));
                return result;
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
                return result;
            }

            try {
                folder.delete();
            } catch (InterruptedException | IOException e) {
                logger.log(Level.WARNING, String.format("Failed to delete folder [%s]", folderName), e);
                return result.setRequeue(true);
            }

        } catch (Exception e) {
            logger.log(Level.INFO, String.format("Unable to delete folder %s, reason %s", folderName, e.getMessage()));
        }
        return result;
    }


    @Override
    public String getControllerName() {
        return "NamespaceController";
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
