package io.alauda.jenkins.devops.sync.controller;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import hudson.Extension;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1CodeRepoBinding;
import io.alauda.devops.java.client.models.V1alpha1CodeRepoBindingList;
import io.alauda.devops.java.client.models.V1alpha1CodeRepoService;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.CodeRepoBindingClient;
import io.alauda.jenkins.devops.sync.constants.CodeRepoBindingPhase;
import io.alauda.jenkins.devops.sync.controller.util.InformerUtils;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.alauda.jenkins.devops.sync.mapper.converter.GitProviderConfigServers;
import java.util.*;
import jenkins.model.Jenkins;
import hudson.security.ACL;
import hudson.security.ACLContext;

@Extension
public class CodeRepoBindingController
        implements ResourceSyncController, ConnectionAliveDetectTask.HeartbeatResourceDetector {

    private static final Logger logger = LoggerFactory.getLogger(CodeRepoBindingController.class);
    private LocalDateTime lastEventComingTime;
    private static final String CONTROLLER_NAME = "CodeRepoBindingController";

    @Override
    public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        SharedIndexInformer<V1alpha1CodeRepoBinding> informer = InformerUtils.getExistingSharedIndexInformer(factory,
                V1alpha1CodeRepoBinding.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(
                    callGeneratorParams -> api.listCodeRepoBindingForAllNamespacesCall(null, null, null, null, null,
                            null, callGeneratorParams.resourceVersion, callGeneratorParams.timeoutSeconds,
                            callGeneratorParams.watch, null, null),
                    V1alpha1CodeRepoBinding.class, V1alpha1CodeRepoBindingList.class,
                    TimeUnit.MINUTES.toMillis(AlaudaSyncGlobalConfiguration.get().getResyncPeriod()));
        }

        CodeRepoBindingClient client = new CodeRepoBindingClient(informer);
        Clients.register(V1alpha1CodeRepoBinding.class, client);

        Controller controller = ControllerBuilder.defaultBuilder(factory).watch(workQueue -> ControllerBuilder
                .controllerWatchBuilder(V1alpha1CodeRepoBinding.class, workQueue)
                .withWorkQueueKeyFunc(
                        binding -> new Request(binding.getMetadata().getNamespace(), binding.getMetadata().getName()))
                .withOnAddFilter(binding -> {
                    if (binding.getStatus().getPhase().equals(CodeRepoBindingPhase.CREATING)) {
                        logger.debug("[{}] phase of CodeRepoBinding '{}/{}' is {}, will skip it", CONTROLLER_NAME,
                                binding.getMetadata().getNamespace(), binding.getMetadata().getName(),
                                CodeRepoBindingPhase.CREATING);
                        return false;
                    }

                    logger.debug("[{}] receives event: Add; CodeRepoBinding '{}/{}'", CONTROLLER_NAME,
                            binding.getMetadata().getNamespace(), binding.getMetadata().getName());
                    return true;
                }).withOnUpdateFilter((oldbinding, newbinding) -> {
                    String namespace = oldbinding.getMetadata().getNamespace();
                    String name = oldbinding.getMetadata().getName();
                    if (oldbinding.getMetadata().getResourceVersion()
                            .equals(newbinding.getMetadata().getResourceVersion())) {
                        logger.debug(
                                "[{}] resourceVersion of CodeRepoBinding '{}/{}' is equal, will skip update event for it",
                                CONTROLLER_NAME, namespace, name);
                        return false;
                    }

                    lastEventComingTime = LocalDateTime.now();

                    if (newbinding.getStatus().getPhase().equals(CodeRepoBindingPhase.CREATING)) {
                        logger.debug("[{}] phase of CodeRepoBinding '{}/{}' is {}, will skip it", CONTROLLER_NAME,
                                namespace, name, CodeRepoBindingPhase.CREATING);
                        return false;
                    }

                    logger.debug("[{}] receives event: Update; CodeRepoBinding '{}/{}'", CONTROLLER_NAME, namespace,
                            name);

                    return true;
                }).withOnDeleteFilter((binding, aBoolean) -> {
                    logger.debug("[{}] receives event: Delete; CodeRepoBinding '{}/{}'", CONTROLLER_NAME,
                            binding.getMetadata().getNamespace(), binding.getMetadata().getName());
                    return true;
                }).build()).withReconciler(new CodeRepoBindingReconciler(new Lister<>(informer.getIndexer())))
                .withName(CONTROLLER_NAME).withWorkerCount(4).withReadyFunc(Clients::allRegisteredResourcesSynced)
                .build();

        managerBuilder.addController(controller);
    }

    @Override
    public LocalDateTime lastEventComingTime() {
        return lastEventComingTime;
    }

    @Override
    public String resourceName() {
        return "CodeRepoBinding";
    }

    static class CodeRepoBindingReconciler implements Reconciler {
        private Lister<V1alpha1CodeRepoBinding> lister;
        private Jenkins jenkins;

        public CodeRepoBindingReconciler(Lister<V1alpha1CodeRepoBinding> lister) {
            this.lister = lister;
            this.jenkins = Jenkins.getInstance();
        }

        @Override
        public Result reconcile(Request request) {
            String namespace = request.getNamespace();
            String name = request.getName();
            GitProviderConfigServers soureconfig = null;
            boolean succeed = true;
            V1alpha1CodeRepoBinding cb = lister.namespace(namespace).get(name);
            try (ACLContext ignored = ACL.as(ACL.SYSTEM)) {
                if (cb == null) {
                    logger.debug(
                            "[{}] Cannot found CodeRepobinding '{}/{}' in local lister, will try to remove it's correspondent sourceconfing server. ",
                            getControllerName(), namespace, name);
                    jenkins.getExtensionList(GitProviderConfigServers.class).stream().forEach(config -> {
                        config.deleteServer(namespace, name);
                    });
                    return new Result(false);
                }
                V1alpha1CodeRepoService codeService = Clients.get(V1alpha1CodeRepoService.class).lister()
                        .get(cb.getSpec().getCodeRepoService().getName());
                Optional<GitProviderConfigServers> sourceconfigOpt = jenkins
                        .getExtensionList(GitProviderConfigServers.class).stream()
                        .filter(service -> service.accept(codeService.getSpec().getType())).findFirst();
                if (sourceconfigOpt.isPresent()) {
                    soureconfig = sourceconfigOpt.get();
                    succeed = soureconfig.createOrUpdateServer(cb);
                }
            } catch (Exception e) {
                logger.info("[{}] Unable to update coderepobinding {}/{}, reason {}", CONTROLLER_NAME, namespace, name,
                        e.getMessage());
            }
            return new Result(!succeed);
        }

        private String getControllerName() {
            return CONTROLLER_NAME;
        }
    }
}
