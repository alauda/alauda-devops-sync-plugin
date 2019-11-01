package io.alauda.jenkins.devops.sync.controller;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.informer.SharedInformerFactory;
import javax.annotation.Nonnull;

public interface ResourceController extends ExtensionPoint {

  void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory);

  @Nonnull
  static ExtensionList<ResourceController> all() {
    return ExtensionList.lookup(ResourceController.class);
  }
}
