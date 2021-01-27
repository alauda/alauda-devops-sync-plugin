package io.alauda.jenkins.devops.sync.tasks.period;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import io.alauda.jenkins.devops.sync.exception.ExceptionUtils;
import io.alauda.jenkins.devops.sync.function.AlaudaPipelineFilter;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import io.kubernetes.client.openapi.ApiException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class OrphanJobCheckTask extends AsyncPeriodicWork {

  private static final Logger LOGGER = LoggerFactory.getLogger(OrphanJobCheckTask.class.getName());

  public OrphanJobCheckTask() {
    super("OrphanJobCheckTask");
  }

  private List<Item> orphanList = new ArrayList<>();

  @Override
  protected void execute(TaskListener listener) throws IOException, InterruptedException {
    LOGGER.info("Start to scan orphan items.");
    orphanList.clear();

    ResourceControllerManager resourceControllerManager =
        ResourceControllerManager.getControllerManager();
    if (!resourceControllerManager.isStarted()) {
      LOGGER.info(
          "SyncManager has not started yet, reason {}, will skip this Orphan Job check",
          resourceControllerManager.getManagerStatus());
      return;
    }

    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      scanOrphanItems();
    }
  }

  private void scanOrphanItems() {
    Jenkins jenkins = Jenkins.getInstance();
    List<Folder> folders = jenkins.getItems(Folder.class);

    folders.forEach(
        folder ->
            folder
                .getItems()
                .stream()
                .filter(new AlaudaPipelineFilter())
                .forEach(
                    item -> {
                      WorkflowJobProperty pro =
                          WorkflowJobUtils.getAlaudaProperty((WorkflowJob) item);

                      String ns = pro.getNamespace();
                      String name = pro.getName();

                      V1alpha1PipelineConfig pc =
                          Clients.get(V1alpha1PipelineConfig.class)
                              .lister()
                              .namespace(ns)
                              .get(name);
                      if (pc == null) {
                        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
                        V1alpha1PipelineConfig newer;
                        try {
                          newer = api.readNamespacedPipelineConfig(name, ns, null, null, null);

                          if (newer == null) {
                            LOGGER.info(
                                "Unable to get PipelineConfig '{}/{}', will delete it", ns, name);
                            orphanList.add(item);
                          }
                        } catch (ApiException e) {
                          if (ExceptionUtils.isResourceNotFoundException(e)) {
                            LOGGER.info(
                                "Unable to get newer PipelineConfig '{}/{}' from apiserver, will delete it, reason: {}",
                                ns,
                                name,
                                e.getMessage());
                            orphanList.add(item);
                          } else {
                            LOGGER.info(
                                "Unable to check if PipelineConfig '{}/{}' exists, reason: {}",
                                ns,
                                name,
                                e.getMessage());
                          }
                        }
                      }
                    }));

    LOGGER.info("Start to remove orphan items, total numbers {}.", orphanList.size());
    orphanList.forEach(
        item -> {
          try {
            item.delete();

            LOGGER.info("Remove orphan item [{}].", item.getFullName());
          } catch (IOException e) {
            e.printStackTrace();
          } catch (InterruptedException e) {
            e.printStackTrace();
            Thread.currentThread().interrupt();
          }
        });
  }

  @Override
  public long getRecurrencePeriod() {
    return TimeUnit.MINUTES.toMillis(60);
  }
}
