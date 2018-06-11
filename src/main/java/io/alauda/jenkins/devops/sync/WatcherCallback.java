/**
 * Copyright (C) 2018 Red Hat, Inc.
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
package io.alauda.jenkins.devops.sync;

import io.alauda.jenkins.devops.sync.watcher.BaseWatcher;
import io.alauda.kubernetes.client.KubernetesClientException;
import io.alauda.kubernetes.client.Watcher;
import okhttp3.OkHttpClient;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author suren
 * @param <T>
 */
public class WatcherCallback<T> implements Watcher<T> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final BaseWatcher watcher;
    private final ScheduledExecutorService service;
    private final AtomicInteger retryAttempt = new AtomicInteger(0);
    private final static int maxIntervalExponent = 5;
    private final int reconnectLimit = -1;
    private final int reconnectInterval = 1000;

    private ScheduledFuture<Boolean> future;
    private String namespace;

    public WatcherCallback(BaseWatcher w, String namespace) {
        watcher = w;
        this.namespace = namespace;
        service = Executors.newScheduledThreadPool(1);
        Logger.getLogger(OkHttpClient.class.getName()).setLevel(Level.FINE);
    }

    @Override
    public void eventReceived(Action action, T resource) {
        watcher.eventReceived(action, resource);
    }

    @Override
    public void onClose(KubernetesClientException cause) {
        if(cause != null) {
            logger.warning(() -> "Client is closed, prepare to re-watch");

            reWatch();
        } else if(isReWatching()) {
            // close by user
            future.cancel(true);
            future = null;
        }
    }

    private void reWatch() {
        if(isReWatching()) {
            return;
        }

        future = service.schedule(() -> {
            watcher.stop();

            try {
                watcher.watch(namespace);

                retryAttempt.set(0);
            } catch (KubernetesClientException e) {
                service.schedule(() -> reWatch(), nextInterval(), TimeUnit.MILLISECONDS);

                return false;
            }

            GlobalPluginConfiguration.get().reloadNamespaces();
            watcher.init(GlobalPluginConfiguration.get().getNamespaces());

            return true;
        }, nextInterval(), TimeUnit.MILLISECONDS);
    }

    private long nextInterval() {
        int exponentOfTwo = retryAttempt.getAndIncrement();
        if (exponentOfTwo > maxIntervalExponent) {
            exponentOfTwo = maxIntervalExponent;
        }

        long ret = reconnectInterval * (1 << exponentOfTwo);

        logger.info("Current rewatch backoff is " + ret + " milliseconds (T" + exponentOfTwo + ")");
        return ret;
    }

    private boolean isReWatching() {
        return (future != null && !future.isCancelled() && !future.isDone());
    }
}
