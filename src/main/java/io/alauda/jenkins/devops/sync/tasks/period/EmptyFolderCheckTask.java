package io.alauda.jenkins.devops.sync.tasks.period;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import io.alauda.jenkins.devops.sync.exception.ExceptionUtils;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.models.V1Namespace;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class EmptyFolderCheckTask extends AsyncPeriodicWork {

  private static final Logger logger =
      LoggerFactory.getLogger(EmptyFolderCheckTask.class.getName());

  public EmptyFolderCheckTask() {
    super("EmptyFolderCheckTask");
  }

  @Override
  protected void execute(TaskListener listener) throws IOException, InterruptedException {
    logger.debug("Start to check empty folder");
    ResourceControllerManager resourceSyncManager =
        ResourceControllerManager.getControllerManager();

    if (!resourceSyncManager.isStarted()) {
      logger.info(
          "SyncManager has not started yet, reason {}, will skip this empty folder check",
          resourceSyncManager.getManagerStatus());
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

  private List<Folder> listFoldersShouldDelete() {
    List<Folder> folders;
    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      folders = Jenkins.getInstance().getItems(Folder.class);
    }

    if (folders == null) {
      logger.debug("No folder found in Jenkins, will skip this empty folder check");
      return Collections.emptyList();
    }

    return folders
        .stream()
        .filter(
            folder ->
                folder
                        .getProperties()
                        .stream()
                        .anyMatch(
                            property ->
                                // we should delete folders be marked as dirty.
                                // Folder will be marked as dirty when we received Delete event of
                                // namespace.
                                property instanceof AlaudaFolderProperty
                                    && ((AlaudaFolderProperty) property).isDirty())
                    // we should delete folders haven't match namespace
                    || noMatchedNamespaceInK8s(folder.getName()))
        // if folder contains item that created by user, we should not delete this folder
        .filter(
            folder ->
                folder
                    .getItems()
                    .stream()
                    .noneMatch(
                        item -> {
                          if (item instanceof WorkflowJob) {
                            return ((WorkflowJob) item).getProperty(WorkflowJobProperty.class)
                                == null;
                          } else if (item instanceof WorkflowMultiBranchProject) {
                            return ((WorkflowMultiBranchProject) item)
                                    .getProperties()
                                    .get(MultiBranchProperty.class)
                                == null;
                          }
                          return false;
                        }))
        .collect(Collectors.toList());
  }

  private boolean noMatchedNamespaceInK8s(String target) {
    CoreV1Api api = new CoreV1Api();
    V1Namespace ns;
    try {
      ns = api.readNamespace(target, null, null, null);

      return ns == null;
    } catch (ApiException e) {
      return ExceptionUtils.isResourceNotFoundException(e);
    }
  }

  @Override
  public long getRecurrencePeriod() {
    return TimeUnit.MINUTES.toMillis(10);
  }
}
