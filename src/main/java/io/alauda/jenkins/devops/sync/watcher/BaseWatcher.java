/**
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.watcher;

import static java.net.HttpURLConnection.HTTP_GONE;

import io.alauda.kubernetes.client.KubernetesClientException;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.Watcher;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jenkins.util.Timer;

public abstract class BaseWatcher {
    private final Logger LOGGER = Logger.getLogger(BaseWatcher.class.getName());

    protected ScheduledFuture relister;
    protected final String[] namespaces;
    protected Map<String, Watch> watches;

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public BaseWatcher(String[] namespaces) {
        this.namespaces = namespaces;
        watches = new ConcurrentHashMap<>();
    }

    public abstract Runnable getStartTimerTask();
    
    public abstract <T> void eventReceived(Watcher.Action action, T resource);

    public synchronized void start() {
        // lets do this in a background thread to avoid errors like:
        // Tried proxying
        // io.alauda.jenkins.devops.sync.GlobalPluginConfiguration to support
        // a circular dependency, but it is not an interface.
        Runnable task = getStartTimerTask();
        relister = Timer.get().scheduleAtFixedRate(task, 100, // still do the
                                                              // first run 100
                                                              // milliseconds in
                5 * 60 * 1000, // 1000 ms * 60 seconds * 5 minutes between
                               // subsequent runs
                TimeUnit.MILLISECONDS);
    }

    public synchronized void stop() {
      if (relister != null && !relister.isDone()) {
          relister.cancel(true);
          relister = null;
      }

      Iterator<String> iterator = watches.keySet().iterator();
      while(iterator.hasNext()) {
        String namespace = iterator.next();

        watches.get(namespace).close();
        watches.remove(namespace);
      }
    }

    public synchronized void onClose(KubernetesClientException e, String namespace) {
        //scans of fabric client confirm this call be called with null
        //we do not want to totally ignore this, as the closing of the 
        //watch can effect responsiveness
        LOGGER.info("Watch for type " + this.getClass().getName() + " closed for one of the following namespaces: " + watches.keySet().toString());
        if (e != null) {
            LOGGER.warning(e.toString());

            if (e.getStatus() != null && e.getStatus().getCode() == HTTP_GONE) {
                stop();
                start();
            }
        }
        // clearing the watches here will signal the extending classes
        // to attempt to re-establish the watch the next time they attempt
        // to list; should shield from rapid/repeated close/reopen cycles
        // doing it in this fashion
//        watches.remove(namespace); // because of here is rsync, so it will cause ConcurrentModificationException
    }

    protected boolean hasSlaveLabelOrAnnotation(Map<String, String> map) {
        if (map != null)
            return map.containsKey("role")
                    && map.get("role").equals("jenkins-slave");
        return false;
    }

}
