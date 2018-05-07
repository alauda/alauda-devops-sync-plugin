package io.alauda.jenkins.devops.sync.watcher;

import hudson.triggers.SafeTimerTask;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.kubernetes.api.model.Namespace;
import io.alauda.kubernetes.client.Watch;
import io.alauda.kubernetes.client.Watcher.Action;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class NamespaceWatcher extends BaseWatcher {
  private final Logger LOGGER = Logger.getLogger(NamespaceWatcher.class.getName());
  private JenkinsBindingWatcher jenkinsBindingWatcher;
  private List<String> namespaceList;

  public NamespaceWatcher(String[] namespaces) {
    super(namespaces);

    namespaceList = new CopyOnWriteArrayList<>();
  }

  public Runnable getStartTimerTask() {
    return new NamespaceWatcher.NamespaceWatcherTask();
  }

  public <T> void eventReceived(Action action, T resource) {
    Namespace namespace = (Namespace)resource;
    String name = namespace.getMetadata().getName();
    this.LOGGER.info("namespace : " + name + "; action : " + action.name());

    switch (action) {
      case DELETED:
        namespaceList.remove(name);
        break;
      case ADDED:
        namespaceList.add(name);
    }

    restartJenkinsBindingWatcher(namespaceList.toArray(new String[]{}));
  }

  private class NamespaceWatcherTask extends SafeTimerTask {
    private NamespaceWatcherTask() {
    }

    protected void doRun() throws Exception {
      if (!CredentialsUtils.hasCredentials()) {
        NamespaceWatcher.this.LOGGER.info("No Alauda Kubernetes Token credential defined.");
      } else {
        NamespaceWatcher.this.watches.put("namespace", this.getWatch((String)null));

        namespaceList.addAll(Arrays.asList(namespaces));
        restartJenkinsBindingWatcher(namespaces);
      }
    }

    private Watch getWatch(String namespace) {
      return (Watch)AlaudaUtils.getAuthenticatedAlaudaClient().namespaces().watch(new WatcherCallback(NamespaceWatcher.this, namespace));
    }
  }

  private void restartJenkinsBindingWatcher(String[] namespaces) {
    if(jenkinsBindingWatcher != null) {
      jenkinsBindingWatcher.stop();
    }

    jenkinsBindingWatcher = new JenkinsBindingWatcher(namespaces);
    jenkinsBindingWatcher.start();
  }
}
