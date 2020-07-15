package io.alauda.jenkins.devops.sync.listener;

import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy;
import com.cloudbees.hudson.plugins.folder.computed.OrphanedItemStrategy;
import hudson.Extension;
import hudson.model.Item;
import hudson.util.PersistedList;
import io.alauda.devops.java.client.models.V1alpha1MultiBranchPipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.models.V1Status;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
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

      // setting project factory
      BranchProjectFactory<WorkflowJob, WorkflowRun> factory = item.getProjectFactory();
      if (factory instanceof WorkflowBranchProjectFactory) {
        String scriptPath = ((WorkflowBranchProjectFactory) factory).getScriptPath();
        newPc.getSpec().getStrategy().getJenkins().jenkinsfile(scriptPath);
      }

      V1alpha1MultiBranchPipeline multiBranchPipeline =
          newPc.getSpec().getStrategy().getJenkins().getMultiBranch();
      PersistedList<BranchSource> sourcesList = item.getSourcesList();
      // only support one branch source for now
      BranchSource branchSource = sourcesList.isEmpty() ? null : sourcesList.get(0);
      if (branchSource != null) {
        branchSource.getSource();
        SCMSource scmSource = branchSource.getSource();

        try {
          Method getTraits = scmSource.getClass().getMethod("getTraits");
          List<SCMSourceTrait> traits = (List<SCMSourceTrait>) getTraits.invoke(scmSource);
          if (traits != null) {
            for (SCMSourceTrait trait : traits) {
              if (trait instanceof RegexSCMHeadFilterTrait) {
                String regex = ((RegexSCMHeadFilterTrait) trait).getRegex();
                multiBranchPipeline.getBehaviours().filterExpression(regex);
              }
            }
          }
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
          logger.warning(
              String.format(
                  "Unable to set traits for PipelineConfig %s, reason: %s",
                  nsName, e.getMessage()));
        }
      }

      // setting orphaned item strategy
      OrphanedItemStrategy orphanedItemStrategy = item.getOrphanedItemStrategy();
      if (orphanedItemStrategy instanceof DefaultOrphanedItemStrategy) {
        DefaultOrphanedItemStrategy defStrategy =
            ((DefaultOrphanedItemStrategy) orphanedItemStrategy);
        if (defStrategy.isPruneDeadBranches()) {
          int days = defStrategy.getDaysToKeep();
          int max = defStrategy.getNumToKeep();
          multiBranchPipeline.getOrphaned().days(days).max(max);
        } else {
          multiBranchPipeline.setOrphaned(null);
        }
      } else {
        multiBranchPipeline.setOrphaned(null);
      }

      Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
      pc.setSpec(newPc.getSpec());

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
