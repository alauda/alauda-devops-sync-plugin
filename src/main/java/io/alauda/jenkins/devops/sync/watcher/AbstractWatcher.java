package io.alauda.jenkins.devops.sync.watcher;

import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.dsl.internal.WatchConnectionManager;

public abstract class AbstractWatcher implements BaseWatcher {
    private Watch watcher;

    public Watch getWatcher() {
        return watcher;
    }

    public void setWatcher(Watch watcher) {
        // stopping current watcher if existing
        stop();
        this.watcher = watcher;

        if(watcher instanceof WatchConnectionManager) {
            WatchConnectionManager mgr = (WatchConnectionManager) watcher;
        }
    }

    @Override
    public void stop(){
        if(watcher != null) {
            watcher.close();
            watcher = null;
        }
    }

    public abstract WatcherCallback getWatcherCallback();
}
