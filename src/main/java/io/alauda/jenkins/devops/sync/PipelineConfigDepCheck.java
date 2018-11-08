package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.init.InitMilestone;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.alauda.jenkins.devops.sync.watcher.ResourcesCache;
import io.alauda.kubernetes.api.model.Condition;
import io.alauda.kubernetes.api.model.Namespace;
import io.alauda.kubernetes.api.model.PipelineConfigList;
import jenkins.model.Jenkins;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Logger;

/**
 * Check dependency of PipelineConfig that created by a template.
 */
public class PipelineConfigDepCheck implements Callable<Boolean> {
    private static final Logger LOGGER = Logger.getLogger(PipelineConfigDepCheck.class.getName());
    private AlaudaDevOpsClient client;

    private void handlePipelineConfig(String namespace) {
        PipelineConfigList list = client.pipelineConfigs().inNamespace(namespace).list();
        if(list == null || list.getItems() == null) {
            return;
        }

        list.getItems().stream().forEach(pipelineConfig -> {
            if(!ResourcesCache.getInstance().isBinding(pipelineConfig)) {
                return;
            }

            List<Condition> conditions = new ArrayList<>();
            PipelineConfigUtils.dependencyCheck(pipelineConfig, conditions);

            client.pipelineConfigs().inNamespace(namespace)
                    .withName(pipelineConfig.getMetadata().getName())
                    .edit().editStatus().withConditions(conditions)
                    .withPhase(conditions.size() == 0 ? PipelineConfigPhase.READY : PipelineConfigPhase.ERROR)
                    .withMessage(conditions.size() == 0 ? "" : ErrorMessages.PLUGIN_ERROR)
                    .endStatus().done();
        });
    }

    @Override
    public Boolean call() {
        Jenkins j = Jenkins.getInstance();
        InitMilestone initLevel = j.getInitLevel();
        if (initLevel != InitMilestone.COMPLETED) {
            return false;
        }

        List<Folder> folders = j.getItems(Folder.class);
        if(folders == null || folders.size() == 0) {
            LOGGER.warning("Found zero folder in Jenkins.");
            return true;
        }

        client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            AlaudaUtils.initializeAlaudaDevOpsClient(AlaudaSyncGlobalConfiguration.get().getServer());
            LOGGER.warning("Can't get the Kubernetes client.");
            return false;
        }

        folders.stream().filter(folder -> folder.getItems().size() > 0).forEach(folder -> {
            String ns = folder.getName();

            Namespace namespace = client.namespaces().withName(ns).get();
            if(namespace != null) {
                handlePipelineConfig(ns);
            }
        });

        return true;
    }
}
