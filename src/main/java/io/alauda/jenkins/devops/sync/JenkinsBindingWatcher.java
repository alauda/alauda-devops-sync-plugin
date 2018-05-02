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
package io.alauda.jenkins.devops.sync;

import hudson.triggers.SafeTimerTask;
import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
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

  @Override
  public <T> void eventReceived(Watcher.Action action, T resource) {
    JenkinsBinding jenkinsBinding = (JenkinsBinding) resource;

    LOGGER.info("JenkinsBindingWatcher receive action : " + action.name() + "; resource : " + jenkinsBinding.getMetadata().getName());

    GlobalPluginConfiguration.get().configChange();
  }

  private class JenkinsBindingWatcherTask extends SafeTimerTask {
    @Override
    protected void doRun() throws Exception {
      if (!CredentialsUtils.hasCredentials()) {
        LOGGER.info("No Alauda Kubernetes Token credential defined.");
        return;
      }

      for (String namespace : namespaces) {
        watches.put(namespace, getWatch(namespace));
      }
    }

    private Watch getWatch(String namespace) {
      JenkinsBindingList jenkinsBindingList = AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings().inNamespace(namespace).list();

      String resourceVersion = "0";
      if(jenkinsBindingList != null) {
        jenkinsBindingList.getMetadata().getResourceVersion();
      }

      return AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings().inNamespace(namespace).withResourceVersion(resourceVersion).watch(
          new WatcherCallback<JenkinsBinding>(JenkinsBindingWatcher.this, namespace));
    }
  }
}
