package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBindingList;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsBindingClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Extension
public class JenkinsBindingController
    implements ResourceController, ConnectionAliveDetectTask.HeartbeatResourceDetector {

  private LocalDateTime lastEventComingTime;

  @Override
  public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    SharedIndexInformer<V1alpha1JenkinsBinding> informer =
        factory.getExistingSharedIndexInformer(V1alpha1JenkinsBinding.class);
    if (informer == null) {
      informer =
          factory.sharedIndexInformerFor(
              callGeneratorParams ->
                  api.listJenkinsBindingForAllNamespacesCall(
                      null,
                      null,
                      null,
                      "jenkins=" + AlaudaSyncGlobalConfiguration.get().getJenkinsService(),
                      null,
                      null,
                      callGeneratorParams.resourceVersion,
                      callGeneratorParams.timeoutSeconds,
                      callGeneratorParams.watch,
                      null,
                      null),
              V1alpha1JenkinsBinding.class,
              V1alpha1JenkinsBindingList.class,
              TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
    }

    JenkinsBindingClient client = new JenkinsBindingClient(informer);
    Clients.register(V1alpha1JenkinsBinding.class, client);

    Controller controller =
        ControllerBuilder.defaultBuilder(factory)
            .watch(
                workQueue ->
                    ControllerBuilder.controllerWatchBuilder(
                            V1alpha1JenkinsBinding.class, workQueue)
                        .withWorkQueueKeyFunc(
                            jenkinsBinding ->
                                new Request(
                                    jenkinsBinding.getMetadata().getName(),
                                    jenkinsBinding.getMetadata().getNamespace()))
                        .withOnUpdateFilter(
                            (oldJenkinsBinding, newJenkinsBinding) -> {
                              if (!oldJenkinsBinding
                                  .getMetadata()
                                  .getResourceVersion()
                                  .equals(newJenkinsBinding.getMetadata().getResourceVersion())) {
                                lastEventComingTime = LocalDateTime.now();
                              }
                              return true;
                            })
                        .build())
            .withReconciler(request -> new Result(false))
            .withName("JenkinsBindingController")
            .withWorkerCount(1)
            .build();

    managerBuilder.addController(controller);
  }

  @Override
  public LocalDateTime lastEventComingTime() {
    return lastEventComingTime;
  }

  @Override
  public String resourceName() {
    return "JenkinsBinding";
  }

  @Override
  public boolean hasResourceExists() throws ApiException {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    V1alpha1JenkinsBindingList bindingList =
        api.listJenkinsBindingForAllNamespaces(null, null, null, null, 1, null, "0", null, null);

    if (bindingList == null
        || bindingList.getItems() == null
        || bindingList.getItems().size() == 0) {
      return false;
    }

    return true;
  }
}
