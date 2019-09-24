package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1CodeRepository;
import io.alauda.devops.java.client.models.V1alpha1CodeRepositoryList;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.CodeRepositoryClient;
import io.alauda.jenkins.devops.sync.controller.util.InformerUtils;
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
public class CodeRepositoryController implements ResourceSyncController, ConnectionAliveDetectTask.HeartbeatResourceDetector {

    private LocalDateTime lastEventComingTime;

    @Override
    public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        SharedIndexInformer<V1alpha1CodeRepository> informer = InformerUtils.getExistingSharedIndexInformer(factory, V1alpha1CodeRepository.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(
                    callGeneratorParams -> api.listCodeRepositoryForAllNamespacesCall(
                            null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            callGeneratorParams.resourceVersion,
                            callGeneratorParams.timeoutSeconds,
                            callGeneratorParams.watch,
                            null,
                            null
                    ), V1alpha1CodeRepository.class, V1alpha1CodeRepositoryList.class, TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
        }


        CodeRepositoryClient client = new CodeRepositoryClient(informer);
        Clients.register(V1alpha1CodeRepository.class, client);

        Controller controller =
                ControllerBuilder.defaultBuilder(factory)
                        .watch(workQueue -> ControllerBuilder.controllerWatchBuilder(V1alpha1CodeRepository.class, workQueue)
                                .withWorkQueueKeyFunc(repository ->
                                        new Request(repository.getMetadata().getNamespace(),
                                                repository.getMetadata().getName()))
                                .withOnUpdateFilter((oldCodeRepository, newCodeRepository) -> {
                                    if (!oldCodeRepository.getMetadata().getResourceVersion().equals(newCodeRepository.getMetadata().getResourceVersion())) {
                                        lastEventComingTime = LocalDateTime.now();
                                    }
                                    return true;
                                })
                                .build())
                        .withReconciler(request -> new Result(false))
                        .withName("CodeRepositoryController")
                        .withWorkerCount(1)
                        .withReadyFunc(informer::hasSynced)
                        .build();

        managerBuilder.addController(controller);
    }

    @Override
    public LocalDateTime lastEventComingTime() {
        return lastEventComingTime;
    }

    @Override
    public String resourceName() {
        return "CodeRepository";
    }
}
