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

import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.Watcher;

import java.util.List;
import java.util.logging.Logger;

/**
 * @author suren
 */
public class JenkinsBindingWatcher implements BaseWatcher {
    private final Logger LOGGER = Logger.getLogger(JenkinsBindingWatcher.class.getName());
    private Watch watcher;

    @Override
    public <T> void eventReceived(Watcher.Action action, T resource) {
        JenkinsBinding jenkinsBinding = (JenkinsBinding) resource;

        LOGGER.info("JenkinsBindingWatcher receive action : " + action + "; resource : "
                + jenkinsBinding.getMetadata().getName());

        switch (action) {
            case ADDED:
                ResourcesCache.getInstance().addNamespace(jenkinsBinding);
                break;
            case DELETED:
                ResourcesCache.getInstance().removeNamespace(jenkinsBinding);
                break;
        }
    }

    @Override
    public void watch() {
        if (!CredentialsUtils.hasCredentials()) {
            LOGGER.info("No Alauda Kubernetes Token credential defined.");
            return;
        }

        JenkinsBindingList jenkinsBindingList = AlaudaUtils.getAuthenticatedAlaudaClient()
                .jenkinsBindings().inAnyNamespace().list();

        String resourceVersion = "0";
        if(jenkinsBindingList != null) {
            resourceVersion = jenkinsBindingList.getMetadata().getResourceVersion();

            cacheBindings(jenkinsBindingList);
        } else {
            LOGGER.warning("Can not found JenkinsBindingList.");
        }

        watcher = AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings()
                .inAnyNamespace()
                .withResourceVersion(resourceVersion)
                .watch(new WatcherCallback<JenkinsBinding>(JenkinsBindingWatcher.this, null));

        LOGGER.info("JenkinsBindingWatcher already added.");
    }

    private void cacheBindings(JenkinsBindingList jenkinsBindingList) {
        List<JenkinsBinding> items = jenkinsBindingList.getItems();
        if(items == null || items.size() == 0) {
            LOGGER.warning("JenkinsBindingList is empty!");
            return;
        }

        LOGGER.info("Find JenkinsBinding " + items.size());

        for(JenkinsBinding binding : items) {
            ResourcesCache.getInstance().addJenkinsBinding(binding);
        }
    }

    @Override
    public void init(String[] namespaces){
        //don't need init anything here
    }

    @Override
    public void stop(){
        if(watcher != null) {
            watcher.close();
        }
    }
}
