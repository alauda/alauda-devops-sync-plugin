package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Item;
import hudson.model.TaskListener;
import hudson.security.ACL;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.watcher.ResourcesCache;
import io.alauda.kubernetes.api.model.PipelineConfig;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
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
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            LOGGER.severe("AlaudaDevOpsClient is null, skip scan orphan items.");
            return;
        }

        LOGGER.info("Start to scan orphan items.");
        orphanList.clear();

        final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
        try {
            scanOrphanItems(client);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private void scanOrphanItems(AlaudaDevOpsClient client) {
        Jenkins jenkins = Jenkins.getInstance();
        List<Folder> folders = jenkins.getItems(Folder.class);

        folders.forEach(folder -> {
            folder.getItems().stream().filter(item -> {
                if(!(item instanceof WorkflowJob)) {
                    return false;
                }

                WorkflowJobProperty pro =
                        ((WorkflowJob) item).getProperty(WorkflowJobProperty.class);
                return pro != null && pro.isValid();
            }).forEach(item ->{
                WorkflowJobProperty pro =
                        ((WorkflowJob) item).getProperty(WorkflowJobProperty.class);

                String ns = pro.getNamespace();
                String name = pro.getName();
                String uid = pro.getUid();

                ResourcesCache cache = ResourcesCache.getInstance();

                PipelineConfig pc = cache.getPipelineConfig(ns, name);
                if(pc == null) {
                    PipelineConfig newer = client.pipelineConfigs()
                            .inNamespace(ns).withName(name).get();

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
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(15);
    }
}
