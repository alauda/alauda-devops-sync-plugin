package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.controller.PipelineConfigController;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import io.kubernetes.client.ApiException;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class OrphanJobCheck extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(OrphanJobCheck.class.getName());

    public OrphanJobCheck() {
        super("OrphanJobCheck");
    }
    private List<Item> orphanList = new ArrayList<>();

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.info("Start to scan orphan items.");
        orphanList.clear();

        final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
        try {
            PipelineConfigController pipelineConfigController = PipelineConfigController.getCurrentPipelineConfigController();
            if (!pipelineConfigController.isValid()) {
                LOGGER.log(Level.INFO, "PipelineConfigController has not synced or is not valid, will skip this Orphan Job check");
                return;
            }

            scanOrphanItems(pipelineConfigController);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private void scanOrphanItems(PipelineConfigController pipelineConfigController) {
        Jenkins jenkins = Jenkins.getInstance();
        List<Folder> folders = jenkins.getItems(Folder.class);

        folders.forEach(folder -> {
            folder.getItems().stream().filter(item -> {
                if(!(item instanceof WorkflowJob)) {
                    return false;
                }

                WorkflowJobProperty pro = WorkflowJobUtils.getAlaudaProperty((WorkflowJob) item);
                return pro != null && pro.isValid();
            }).forEach(item ->{
                WorkflowJobProperty pro = WorkflowJobUtils.getAlaudaProperty((WorkflowJob) item);

                String ns = pro.getNamespace();
                String name = pro.getName();
                String uid = pro.getUid();

                V1alpha1PipelineConfig pc = pipelineConfigController.getPipelineConfig(ns, name);
                if(pc == null) {
                    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
                    V1alpha1PipelineConfig newer = null;
                    try {
                        newer = api.readNamespacedPipelineConfig(
                                name,
                                ns,
                                null,
                                null,
                                null);
                    } catch (ApiException e) {
                        LOGGER.log(Level.FINE, "Unable to get newer pipelineConfig");
                        orphanList.add(item);
                    }

                    if(newer == null || !newer.getMetadata().getUid().equals(uid)) {
                        orphanList.add(item);
                    }
                } else if(!pc.getMetadata().getUid().equals(uid)) {
                    orphanList.add(item);
                }
            });
        });

        LOGGER.info(String.format("Start to remove orphan items[%s].", orphanList.size()));
        orphanList.forEach(item -> {
            try {
                item.delete();

                LOGGER.info(String.format("Remove orphan item [%s].", item.getFullName()));
            } catch (IOException | InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(15);
    }
}
