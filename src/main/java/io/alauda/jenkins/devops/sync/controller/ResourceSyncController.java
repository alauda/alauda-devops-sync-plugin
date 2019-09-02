package io.alauda.jenkins.devops.sync.controller;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.ProcessorListener;
import io.kubernetes.client.informer.cache.SharedProcessor;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public interface ResourceSyncController extends ExtensionPoint {

    void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory);

    @Nonnull
    static ExtensionList<ResourceSyncController> all() {
        return ExtensionList.lookup(ResourceSyncController.class);
    }
}
