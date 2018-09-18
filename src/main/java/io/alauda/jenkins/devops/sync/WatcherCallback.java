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

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class WatcherCallback<T> implements Watcher<T> {
    private final Logger logger = Logger.getLogger(getClass().getName());
    private final BaseWatcher watcher;
    private final String namespace;

    private final ScheduledExecutorService service;
    private final AtomicInteger retryAttempt = new AtomicInteger(0);
    private final static int maxIntervalExponent = 5;
    private final int reconnectLimit = -1;
    private final int reconnectInterval = 1000;

    private ScheduledFuture<Boolean> future;

    public WatcherCallback(BaseWatcher w, String n) {
        watcher = w;
        namespace = n;
        service = Executors.newScheduledThreadPool(1);
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
                watcher.watch();

                retryAttempt.set(0);
            } catch (KubernetesClientException e) {
                service.schedule(() -> reWatch(), nextInterval(), TimeUnit.MILLISECONDS);

                return false;
            }

            AlaudaSyncGlobalConfiguration.get().reloadNamespaces();
            watcher.init(AlaudaSyncGlobalConfiguration.get().getNamespaces());

            return true;
        }, nextInterval(), TimeUnit.MILLISECONDS);
    }

    private long nextInterval() {
        int exponentOfTwo = retryAttempt.getAndIncrement();
        if (exponentOfTwo > maxIntervalExponent) {
            exponentOfTwo = maxIntervalExponent;
        }

        long ret = reconnectInterval * (1L << exponentOfTwo);

        logger.info("Current re-watch back off is " + ret + " milliseconds (T" + exponentOfTwo + ")");
        return ret;
    }

    private boolean isReWatching() {
        return (future != null && !future.isCancelled() && !future.isDone());
    }
}
