package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.alauda.jenkins.devops.sync.watcher.AbstractWatcher;
import io.alauda.kubernetes.client.KubernetesClientException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

@Extension
public class WatcherAliveCheck extends AsyncPeriodicWork {
    private static final Logger LOGGER = Logger.getLogger(WatcherAliveCheck.class.getName());

    public WatcherAliveCheck() {
        super("Watcher alive check work");
    }
    private static final int MINMAM = 1;

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        AlaudaSyncGlobalConfiguration sync = AlaudaSyncGlobalConfiguration.get();
        boolean invalid = !sync.isValid();
        if(invalid) {
            sync.configChange();
            return;
        }

        List<AbstractWatcher> watcherList = new ArrayList<>();
        watcherList.add(sync.getPipelineConfigWatcher());
        watcherList.add(sync.getPipelineWatcher());
        watcherList.add(sync.getSecretWatcher());
        watcherList.add(sync.getJenkinsBindingWatcher());
        watcherList.add(sync.getNamespaceWatcher());

        if(watcherList.contains(null)){
            LOGGER.warning("Get broken watcher, need to restart sync.");
            try {
                sync.configChange();
            } catch (KubernetesClientException e) {
                e.printStackTrace();
            }

            return;
        }

        long timeout = getRecurrencePeriod();
        boolean needRestart = watcherList.stream().anyMatch((watcher) -> {
            long lastEvent = watcher.getWatcherCallback().getLastEvent();
            boolean result = (System.currentTimeMillis() - lastEvent > timeout);
            if(result) {
                LOGGER.warning(watcher.getName() + " did't receive event in " + timeout + "ms!");
            }
            return result;
        });

        if(needRestart) {
            LOGGER.info("Will restart all watchers!");

            sync.configChange();
        } else {
            LOGGER.info("No need to restart watchers.");
        }
    }

    @Override
    public long getRecurrencePeriod() {
        AlaudaSyncGlobalConfiguration sync = AlaudaSyncGlobalConfiguration.get();
        return TimeUnit.MINUTES.toMillis(sync.getWatcherAliveCheck() >= MINMAM ? sync.getWatcherAliveCheck() : MINMAM);
    }
}
