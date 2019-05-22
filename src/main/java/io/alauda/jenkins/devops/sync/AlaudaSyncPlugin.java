package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.Plugin;
import io.alauda.jenkins.devops.sync.listener.JenkinsPipelineJobListener;
import io.alauda.kubernetes.client.KubernetesClientException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

@Extension
public class AlaudaSyncPlugin extends Plugin {
    private static final Logger logger = Logger.getLogger(AlaudaSyncPlugin.class.getName());

    @Override
    public void postInitialize() {
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

        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            while(true) {
                try {
                    Boolean result = pool.submit(new PipelineConfigDepCheck()).get();
                    if(result != null && result) {
                        break;
                    }
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        });
    }
}
