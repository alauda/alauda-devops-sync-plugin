package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.openapi.models.V1Status;
import java.util.logging.Logger;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

@Extension
public class MultiBranchProjectEventHandler
    implements ItemEventHandler<WorkflowMultiBranchProject> {
  private static final Logger logger =
      Logger.getLogger(MultiBranchProjectEventHandler.class.getName());

  @Override
  public boolean accept(Item item) {
    return (item instanceof WorkflowMultiBranchProject);
  }

  @Override
  public void onCreated(WorkflowMultiBranchProject item) {}

  @Override
  public void onUpdated(WorkflowMultiBranchProject item) {
    AlaudaJobProperty property = item.getProperties().get(MultiBranchProperty.class);
    if (property == null) {
      logger.warning(
          String.format(
              "Can't find MultiBranchProperty from %s, skip update event.", item.getFullName()));
      return;
    }

    String ns = property.getNamespace();
    String name = property.getName();
    NamespaceName nsName = new NamespaceName(ns, name);

    V1alpha1PipelineConfig pc =
        Clients.get(V1alpha1PipelineConfig.class).lister().namespace(ns).get(name);
    if (pc == null) {
      logger.warning(String.format("Can't find pipelineconfig %s.", nsName.toString()));
      return;
    }

    synchronized (pc.getMetadata().getUid().intern()) {
      V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

      logger.info(String.format("Going to update pipelineconfig %s.", nsName.toString()));

      newPc.getSpec().setDisabled(item.isDisabled());

      Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);

      logger.info(String.format("Done with update pipelineconfig %s.", nsName.toString()));
    }
  }

  @Override
  public void onDeleted(WorkflowMultiBranchProject item) {
    AlaudaJobProperty property = item.getProperties().get(MultiBranchProperty.class);
    if (property == null) {
      logger.warning(
          String.format(
              "Can't find MultiBranchProperty from %s, skip delete event.", item.getFullName()));
      return;
    }

    String ns = property.getNamespace();
    String name = property.getName();

    V1alpha1PipelineConfig pc =
        Clients.get(V1alpha1PipelineConfig.class).lister().namespace(ns).get(name);
    ;
    if (pc != null) {
      V1Status result = Clients.get(V1alpha1PipelineConfig.class).delete(ns, name);
      logger.info(
          String.format(
              "PipelineConfig [%s]-[%s] delete result [%s].", ns, name, result.toString()));
    }
  }
}
