package io.alauda.jenkins.devops.sync.watcher;

import io.alauda.kubernetes.client.Watch;

public abstract class AbstractWatcher implements BaseWatcher {
    private Watch watcher;

    public Watch getWatcher() {
        return watcher;
    }

    public void setWatcher(Watch watcher) {
        // stopping current watcher if existing
        stop();
        this.watcher = watcher;
    }

    @Override
    public void stop(){
        if(watcher != null) {
            watcher.close();
            watcher = null;
        }
    }
}
