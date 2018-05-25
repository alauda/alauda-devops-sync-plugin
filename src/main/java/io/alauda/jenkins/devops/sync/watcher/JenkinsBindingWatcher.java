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

import hudson.triggers.SafeTimerTask;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.GlobalPluginConfiguration;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
import io.alauda.kubernetes.client.KubernetesClientException;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.Watcher;

import java.util.logging.Logger;

public class JenkinsBindingWatcher extends BaseWatcher {
  private final Logger LOGGER = Logger.getLogger(JenkinsBindingWatcher.class.getName());

  public JenkinsBindingWatcher(String[] namespaces) {
    super(namespaces);
  }

  @Override
  public Runnable getStartTimerTask() {
    return new JenkinsBindingWatcherTask();
  }

  public <T> void eventReceived(Watcher.Action action, T resource) {
    JenkinsBinding jenkinsBinding = (JenkinsBinding) resource;

    LOGGER.info("JenkinsBindingWatcher receive action : " + action + "; resource : " + jenkinsBinding.getMetadata().getName());

//    GlobalPluginConfiguration pluginConfig = GlobalPluginConfiguration.get();
//
//    String jenkinsName = jenkinsBinding.getSpec().getJenkins().getName();
//    String jenkinsService = pluginConfig.getJenkinsService();
//    if(!AlaudaUtils.isBindingToCurrentJenkins(jenkinsBinding)) {
//      LOGGER.info("Omit. Current Jenkins config service is: " + jenkinsService + ", receive JenkinsBinding is: " + jenkinsName);
//      return;
//    }
//
//    String namespace = jenkinsBinding.getMetadata().getNamespace();
//    String[] existsNamespaces = pluginConfig.getNamespaces();
//    if(existsNamespaces != null) {
//      for(String existsNamespace : existsNamespaces) {
//        if(existsNamespace.equals(namespace)) {
//          LOGGER.info("Namespace: " + namespace + " already exists, skip.");
//          return;
//        }
//      }
//    }

    switch (action) {
      case ADDED:
        Cache.getInstance().addNamespace(jenkinsBinding);
        break;
      case DELETED:
        Cache.getInstance().removeNamespace(jenkinsBinding);
        break;
//        GlobalPluginConfiguration.get().reWatchAllNamespace(namespace);
    }
  }

  private class JenkinsBindingWatcherTask extends SafeTimerTask {
    @Override
    protected void doRun() throws Exception {
      if (!CredentialsUtils.hasCredentials()) {
        LOGGER.info("No Alauda Kubernetes Token credential defined.");
        return;
      }

      long beginTime = System.currentTimeMillis();
      int total = namespaces.length;
      LOGGER.info("Prepare to add watcher for " + total + " namespaces.");


        JenkinsBindingList jenkinsBindingList = AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings()
                .list();

        String resourceVersion = "0";
        if(jenkinsBindingList != null) {
            resourceVersion = jenkinsBindingList.getMetadata().getResourceVersion();
        }

        AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings()
                .withResourceVersion(resourceVersion)
                .watch(new WatcherCallback<JenkinsBinding>(JenkinsBindingWatcher.this, null));

//      for (int i = 0; i < total; i++) {
////        if(!AlaudaUtils.isBindingToCurrentJenkins(namespace)) {
////          continue;
////        }
//        String namespace = namespaces[i];
//
//        try {
//          Watch watch = getWatch(namespace);
//
//          watches.put(namespace, watch);
//
//          LOGGER.info("Add JenkinsBindingWatcher for " + namespace + ". --" + i);
//        } catch (KubernetesClientException e) {
//          LOGGER.warning(() -> "Something happened when communicate with k8s. Cause : " + e.getCause());
//        }
//      }

      long endTime = System.currentTimeMillis();
      LOGGER.info("The process of adding watch for JenkinsBinding, take " + (endTime - beginTime) + "ms.");
    }

    /**
     * Create the watcher of the namespace
     * @param namespace namespace resource name
     * @return watcher
     * @throws KubernetesClientException in case of client execute failure
     */
    private Watch getWatch(String namespace) throws KubernetesClientException {
      JenkinsBindingList jenkinsBindingList = AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings().inNamespace(namespace).list();

      String resourceVersion = "0";
      if(jenkinsBindingList != null) {
        resourceVersion = jenkinsBindingList.getMetadata().getResourceVersion();
      }

      return AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings().inNamespace(namespace).withResourceVersion(resourceVersion).watch(
          new WatcherCallback<JenkinsBinding>(JenkinsBindingWatcher.this, namespace));
    }
  }
}
