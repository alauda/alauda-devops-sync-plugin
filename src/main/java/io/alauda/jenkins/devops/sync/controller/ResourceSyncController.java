package io.alauda.jenkins.devops.sync.controller;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import io.alauda.devops.java.client.extend.controller.builder.ControllerManangerBuilder;
import io.kubernetes.client.informer.SharedInformerFactory;

import javax.annotation.Nonnull;

public interface ResourceSyncController extends ExtensionPoint {

    void add(ControllerManangerBuilder managerBuilder, SharedInformerFactory factory);

    @Nonnull
    static ExtensionList<ResourceSyncController> all() {
        return ExtensionList.lookup(ResourceSyncController.class);
    }
}
