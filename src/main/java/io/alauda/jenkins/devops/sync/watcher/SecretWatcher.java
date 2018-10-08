/**
 * Copyright (C) 2018 Alauda.io
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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.Secret;
import io.alauda.kubernetes.api.model.SecretList;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.Watcher;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.WARNING;

/**
 * Watches {@link Secret} objects in Kubernetes and syncs then to Credentials in
 * Jenkins
 * @author suren
 */
public class SecretWatcher extends AbstractWatcher implements BaseWatcher {
    private final Logger logger = Logger.getLogger(getClass().getName());

    private Set<String> namespaceSet;
    private Map<String, String> trackedSecrets;

    @Override
    public void watch() {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            stop();
            logger.severe("client is null, when watch Secret");
            return;
        }

        String resourceVersion = "0";
        SecretList secrets = client.secrets().inAnyNamespace().list();
        if(secrets != null) {
            resourceVersion = secrets.getMetadata().getResourceVersion();
        }

        setWatcher(client.secrets().inAnyNamespace()
                .withResourceVersion(resourceVersion)
                .watch(new WatcherCallback<Secret>(SecretWatcher.this, null)));
    }

    @Override
    public void init(String[] namespaces) {
        if (trackedSecrets == null) {
            trackedSecrets = new ConcurrentHashMap<>();
        }

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return;
        }

        SecretList secrets = client.secrets().inAnyNamespace().list();
        if(secrets == null || secrets.getItems() == null) {
            return;
        }

        namespaceSet = new HashSet(Arrays.asList(namespaces));
        namespaceSet.add(AlaudaSyncGlobalConfiguration.get().getSharedNamespace());

        secrets.getItems().stream().filter((item)->{
            String ns = item.getMetadata().getNamespace();
            return (namespaceSet.contains(ns));
        }).forEach(secret -> {
            try {
                if (validSecret(secret) && shouldProcessSecret(secret)) {
                    upsertCredential(secret);
                    trackedSecrets.put(secret.getMetadata().getUid(),
                            secret.getMetadata().getResourceVersion());
                }
            } catch (Exception e) {
                logger.log(SEVERE, "Failed to update job", e);
            }
        });
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    public synchronized void eventReceived(Watcher.Action action, Secret secret) {
        logger.log(FINE, "Got secret event", action);
        logger.log(FINE, "Got secret object", secret);
        if (!validSecret(secret)) {
            logger.log(WARNING, "Got invalid secret object", secret);
            return;
        }
        if(!ResourcesCache.getInstance().isBinding(secret)) {
            logger.log(WARNING, "Secret is not in namespace list", secret);
            return;
        }

        try {
            switch (action) {
            case ADDED:
                upsertCredential(secret);
                break;
            case DELETED:
                deleteCredential(secret);
                break;
            case MODIFIED:
                modifyCredential(secret);
                break;
            case ERROR:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning("watch for secret " + secret.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught: " + e, e);
        }
    }

    @Override
    public <T> void eventReceived(Watcher.Action action, T resource) {
        Secret secret = (Secret)resource;
        eventReceived(action, secret);
    }

    private void upsertCredential(final Secret secret) throws Exception {
        if (validSecret(secret)) {
            CredentialsUtils.upsertCredential(secret);
            trackedSecrets.put(secret.getMetadata().getUid(), secret
                    .getMetadata().getResourceVersion());
        }
    }

    private void modifyCredential(Secret secret) throws Exception {
        if (validSecret(secret) && shouldProcessSecret(secret)) {
            CredentialsUtils.upsertCredential(secret);
            trackedSecrets.put(secret.getMetadata().getUid(), secret
                    .getMetadata().getResourceVersion());
        }
    }

    private boolean validSecret(Secret secret) {
        if (secret == null) {
            return false;
        }
        ObjectMeta metadata = secret.getMetadata();
        if (metadata != null) {
            String name = metadata.getName();
            String namespace = metadata.getNamespace();
            return name != null && !name.isEmpty() && namespace != null
                    && !namespace.isEmpty();
        }
        return false;
    }

    private boolean shouldProcessSecret(Secret secret) {
        String uid = secret.getMetadata().getUid();
        String rv = secret.getMetadata().getResourceVersion();
        String savedRV = trackedSecrets.get(uid);
        return (savedRV == null || !savedRV.equals(rv));
    }

    private void deleteCredential(final Secret secret) throws Exception {
        trackedSecrets.remove(secret.getMetadata().getUid());
        CredentialsUtils.deleteCredential(secret);
    }
}
