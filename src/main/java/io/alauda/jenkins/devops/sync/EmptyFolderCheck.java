package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import io.kubernetes.client.models.V1Namespace;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class EmptyFolderCheck extends AsyncPeriodicWork {
  private static final Logger logger = LoggerFactory.getLogger(EmptyFolderCheck.class.getName());

  public EmptyFolderCheck() {
    super("EmptyFolderCheck");
  }

  @Override
  protected void execute(TaskListener listener) throws IOException, InterruptedException {
    final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
    try {
      List<Folder> folders = Jenkins.getInstance().getItems(Folder.class);

      if (folders == null) {
        return;
      }

      ResourceControllerManager resourceControllerManager =
          ResourceControllerManager.getControllerManager();

      if (!resourceControllerManager.isStarted()) {
        logger.info(
            "SyncManager has not started yet, reason {}, will skip this Empty Folder Check",
            resourceControllerManager.getPluginStatus());
        return;
      }

      // when the folder is dirty and there is not any custom itemJenkinsPipelineJobListener
      folders
          .stream()
          .filter(
              folder ->
                  folder
                      .getProperties()
                      .stream()
                      .anyMatch(
                          pro -> {
                            String folderName = folder.getName();
                            // delay to remove folder
                            // target namespace doesn't exists anymore
                            return pro instanceof AlaudaFolderProperty
                                && (((AlaudaFolderProperty) pro).isDirty()
                                    || noneMatch(folderName)
                                    || noJenkinsBinding(
                                        folderName)); // namespaces exists but no binding
                          }))
          .filter(
              folder -> {
                Collection<TopLevelItem> items = folder.getItems();
                if (items.isEmpty()) {
                  return true;
                }

                // find custom created item
                return items
                    .stream()
                    .noneMatch(
                        item -> {
                          if (item instanceof WorkflowJob) {
                            return ((WorkflowJob) item).getProperty(WorkflowJobProperty.class)
                                == null;
                          }
                          return false;
                        });
              })
          .forEach(
              folder -> {
                try {
                  folder.delete();
                } catch (IOException e) {
                  logger.warn("Error when deleting folder {}", folder.getName());
                } catch (InterruptedException e) {
                  logger.warn("Error when deleting folder {}", folder.getName());
                  Thread.currentThread().interrupt();
                }
              });
    } finally {
      SecurityContextHolder.setContext(previousContext);
    }
  }

  private boolean noJenkinsBinding(String target) {
    String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
    return Clients.get(V1alpha1JenkinsBinding.class)
        .lister()
        .list()
        .stream()
        .filter(
            jenkinsBinding ->
                jenkinsBinding.getSpec().getJenkins().getName().equals(jenkinsService))
        .map(jenkinsBinding -> jenkinsBinding.getMetadata().getNamespace())
        .distinct()
        .noneMatch(namespace -> namespace.equalsIgnoreCase(target));
  }

  private boolean noneMatch(String target) {
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
