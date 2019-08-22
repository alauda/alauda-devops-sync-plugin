package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.controller.ResourceSyncManager;
import io.kubernetes.client.models.V1Namespace;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Extension
public class EmptyFolderCheckTask extends AsyncPeriodicWork {
    private static final Logger logger = LoggerFactory.getLogger(EmptyFolderCheckTask.class.getName());

    public EmptyFolderCheckTask() {
        super("EmptyFolderCheckTask");
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        logger.debug("Start to check empty folder");
        ResourceSyncManager resourceSyncManager = ResourceSyncManager.getSyncManager();

        if (!resourceSyncManager.isStarted()) {
            logger.info("SyncManager has not started yet, reason {}, will skip this empty folder check", resourceSyncManager.getPluginStatus());
            return;
        }

        List<Folder> folders = listFoldersShouldDelete();

        logger.debug("Found {} folders need to be delete", folders.size());
        for (Folder folder : folders) {
            try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
                logger.debug("Deleting folder {}", folder.getName());
                folder.delete();
            } catch (IOException | InterruptedException e) {
                logger.warn("Failed to delete folder {}, reason {}", folder, e);
                throw e;
            }
        }
    }

    List<Folder> listFoldersShouldDelete() {
        List<Folder> folders;
        try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
            folders = Jenkins.getInstance().getItems(Folder.class);
        }

        if (folders == null) {
            logger.debug("No folder found in Jenkins, will skip this empty folder check");
            return Collections.emptyList();
        }

        return folders.stream()
                .filter(folder ->
                        folder.getProperties()
                                .stream()
                                .anyMatch(property ->
                                        // we should delete folders be marked as dirty
                                        property instanceof AlaudaFolderProperty && ((AlaudaFolderProperty) property).isDirty())
                                // we should delete folders haven't match namespace
                                || noMatchedNamespaceInK8s(folder.getName())
                                // we should delete folders have matched namespace but not matched jenkins bindings
                                || noMatchedJenkinsBindingInNamespace(folder.getName()))
                // if folder contains item that created by user, we should not delete this folder
                .filter(folder ->
                        folder.getItems()
                                .stream()
                                .noneMatch(item -> {
                                    if (item instanceof WorkflowJob) {
                                        return ((WorkflowJob) item).getProperty(WorkflowJobProperty.class) == null;
                                    } else if (item instanceof WorkflowMultiBranchProject) {
                                        return ((WorkflowMultiBranchProject) item).getProperties().get(MultiBranchProperty.class) == null;
                                    }
                                    return false;
                                }))
                .collect(Collectors.toList());
    }

    private boolean noMatchedJenkinsBindingInNamespace(String target) {
        String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
        return Clients.get(V1alpha1JenkinsBinding.class)
                .lister()
                .list()
                .stream()
                .filter(jenkinsBinding -> jenkinsBinding.getSpec().getJenkins().getName().equals(jenkinsService))
                .map(jenkinsBinding -> jenkinsBinding.getMetadata().getNamespace())
                .distinct()
                .noneMatch(namespace -> namespace.equalsIgnoreCase(target));
    }

    private boolean noMatchedNamespaceInK8s(String target) {
        return Clients.get(V1Namespace.class)
                .lister()
                .list()
                .stream()
                .noneMatch(namespace -> namespace.getMetadata().getName().equalsIgnoreCase(target));
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(10);
    }
}
