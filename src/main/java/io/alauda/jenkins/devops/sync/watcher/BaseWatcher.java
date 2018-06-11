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

import io.alauda.kubernetes.client.KubernetesClientException;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.Watcher;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author suren
 */
public abstract class BaseWatcher {
    private Map<String, Watch> watchMap = new ConcurrentHashMap<>();

    public abstract <T> void eventReceived(Watcher.Action action, T resource);

    public abstract void watch(String namespace) throws KubernetesClientException;

    public abstract void init(String[] namespaces);

    public void stop() {
        watchMap.forEach((key, watch) -> {
            watch.close();
        });
        watchMap.clear();
    };

    public void putWatch(String namespace, Watch watch) {
        if(watchMap.containsKey(namespace)) {
            watchMap.get(namespace).close();
            watchMap.remove(namespace);
        }

        watchMap.put(namespace, watch);
    }
}
