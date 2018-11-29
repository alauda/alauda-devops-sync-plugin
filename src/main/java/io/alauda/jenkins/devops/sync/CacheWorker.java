package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.watcher.PipelineConfigWatcher;
import io.alauda.jenkins.devops.sync.watcher.ResourcesCache;
import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigList;
import io.alauda.kubernetes.client.Watcher;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Extension
public class CacheWorker extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(CacheWorker.class.getName());

    public CacheWorker() {
        super("CacheWorker");
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        AlaudaDevOpsClient client = AlaudaUtils.getAlaudaClient();
        if(client == null) {
            LOGGER.severe("Can't get AlaudaDevOpsClient when execute cacheWorker check.");
            return;
        }

        ResourcesCache cache = ResourcesCache.getInstance();
        String jenkinsService = cache.getJenkinsService();
        if(StringUtils.isBlank(jenkinsService)) {
            LOGGER.severe("JenkinsService is empty.");
            return;
        }

        JenkinsBindingList bindingList = client.jenkinsBindings().inAnyNamespace().list();
        List<JenkinsBinding> bindings = null;;
        if(bindingList == null || (bindings = bindingList.getItems()) == null) {
            return;
        }
        bindings.forEach(binding -> cache.addNamespace(binding));

        cache.getNamespaces().forEach(ns -> {
            PipelineConfigList pcList = client.pipelineConfigs().inNamespace(ns).list();
            if(pcList == null) {
                return;
            }

            List<PipelineConfig> pcItems = pcList.getItems();
            if(pcItems == null) {
                return;
            }

            pcItems.stream().filter(item ->
                cache.isBinding(item)
            ).forEach(item -> {
                cache.addPipelineConfig(item);

                final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
                try {
                    checkJob(item, client);
                } finally {
                    SecurityContextHolder.setContext(previousContext);
                }
            });
        });
    }

    private void checkJob(PipelineConfig pc, AlaudaDevOpsClient client) {
        ObjectMeta meta = pc.getMetadata();
        String ns = meta.getNamespace();
        String name = meta.getName();

        boolean notExists = false;
        boolean needModfiy = false;
        String errMsg = null;
        Folder folder = Jenkins.getInstance().getItemByFullName(ns, Folder.class);
        if(folder == null) {
            notExists = true;
        } else {
            String jobName = AlaudaUtils.jenkinsJobName(pc);
            TopLevelItem item = folder.getItem(jobName);
            if(item == null) {
                notExists = true;
            } else {
                AlaudaJobProperty property = null;
                if(item instanceof WorkflowJob) {
                    property = ((WorkflowJob) item).getProperty(WorkflowJobProperty.class);
                } if(item instanceof WorkflowMultiBranchProject) {
                    property = ((WorkflowMultiBranchProject) item).getProperties().get(MultiBranchProperty.class);
                } else {
                    errMsg = "Exists other type job: " + item.getClass().getName();
                }

                if(property != null) {
                    if(!property.getUid().equals(meta.getUid())) {
                        LOGGER.severe(String.format("Found stale workflow job[%s], going to remove it.", name));
                        notExists = true;
                        try {
                            item.delete();
                        } catch (IOException e) {
                            e.printStackTrace();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    } else {
                        needModfiy = !property.getResourceVersion().equals(meta.getResourceVersion());
                    }
                } else {
                    errMsg = "Exists workflow job that created by manual";
                }
            }
        }

        PipelineConfigWatcher watcher = AlaudaSyncGlobalConfiguration.get().getPipelineConfigWatcher();
        if(notExists) {
            watcher.eventReceived(Watcher.Action.ADDED, pc);
        } else if(needModfiy) {
            watcher.eventReceived(Watcher.Action.MODIFIED, pc);
        } else if(errMsg != null) {
            client.pipelineConfigs().inNamespace(ns)
                    .withName(name).edit()
                    .editStatus().withPhase(PipelinePhases.ERROR)
                    .withMessage(errMsg)
                    .endStatus().done();
        }
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(10);
    }
}
