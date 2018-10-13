package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.Plugin;
import io.alauda.jenkins.devops.sync.listener.JenkinsPipelineJobListener;
import io.alauda.kubernetes.client.KubernetesClientException;

import java.util.logging.Logger;

@Extension
public class AlaudaSyncPlugin extends Plugin {
    private static final Logger logger = Logger.getLogger(AlaudaSyncPlugin.class.getName());

    @Override
    public void postInitialize() throws Exception {
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        if(config != null && config.isValid()) {
            try {
                config.configChange();
            } catch (KubernetesClientException e) {
                logger.severe(e.getMessage());
            }
        } else {
            logger.severe("AlaudaSyncGlobalConfiguration is invalid.");
        }
    }
}
