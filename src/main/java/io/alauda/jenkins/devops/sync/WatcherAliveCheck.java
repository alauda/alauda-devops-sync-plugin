package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import io.alauda.jenkins.devops.sync.watcher.AbstractWatcher;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Extension
public class WatcherAliveCheck extends AsyncPeriodicWork {
    public WatcherAliveCheck() {
        super("Watcher alive check work");
    }
    private static final int MINMAM = 1;

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        AlaudaSyncGlobalConfiguration sync = AlaudaSyncGlobalConfiguration.get();
        boolean invalid = !sync.isValid();
        PrintStream log = listener.getLogger();
        if(invalid) {
            log.println("Sync is invalid.");
            return;
        }

        List<AbstractWatcher> watcherList = new ArrayList<>();
        watcherList.add(sync.getPipelineConfigWatcher());
        watcherList.add(sync.getPipelineWatcher());
        watcherList.add(sync.getSecretWatcher());
        watcherList.add(sync.getJenkinsBindingWatcher());

        if(watcherList.contains(null)){
            log.println("Get broken watcher, need to restart sync.");
            sync.configChange();
            return;
        }

        long timeout = getRecurrencePeriod();//TimeUnit.MINUTES.toMillis(5);

        watcherList.forEach(watcher -> {
            if(watcher == null) {
                return;
            }

            long lastEvent = watcher.getWatcherCallback().getLastEvent();
            if(System.currentTimeMillis() - lastEvent > timeout) {
                watcher.stop();

                watcher.watch();
            }
        });
    }

    @Override
    public long getRecurrencePeriod() {
        AlaudaSyncGlobalConfiguration sync = AlaudaSyncGlobalConfiguration.get();
        return TimeUnit.MINUTES.toMillis(sync.getWatcherAliveCheck() >= MINMAM ? sync.getWatcherAliveCheck() : MINMAM);
    }
}
