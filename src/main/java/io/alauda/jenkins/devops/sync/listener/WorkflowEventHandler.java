package io.alauda.jenkins.devops.sync.listener;

import com.google.common.base.Objects;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigSpec;
import io.alauda.devops.java.client.models.V1alpha1PipelineSource;
import io.alauda.devops.java.client.models.V1alpha1PipelineSourceGit;
import io.alauda.devops.java.client.models.V1alpha1PipelineStrategy;
import io.alauda.devops.java.client.models.V1alpha1PipelineStrategyJenkins;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.controller.predicates.BindResourcePredicate;
import io.alauda.jenkins.devops.sync.event.PipelineConfigEvents;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import io.kubernetes.client.models.V1ObjectMeta;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

@Extension
public class WorkflowEventHandler implements ItemEventHandler<WorkflowJob> {

  private static final Logger logger = Logger.getLogger(WorkflowEventHandler.class.getName());
  private JenkinsClient jenkinsClient = JenkinsClient.getInstance();

  @Override
  public boolean accept(Item item) {
    if (!(item instanceof WorkflowJob)) {
      return false;
    }

    ItemGroup<? extends Item> parent = item.getParent();
    return !(parent instanceof WorkflowMultiBranchProject);
  }

  @Override
  public void onCreated(WorkflowJob item) {
    upsertWorkflowJob(item);
  }

  @Override
  public void onUpdated(WorkflowJob item) {
    logger.log(Level.FINE, "onUpdated {0}", item);
    upsertWorkflowJob(item);
  }

  @Override
  public void onDeleted(WorkflowJob item) {

    WorkflowJobProperty property = pipelineConfigProjectForJob(item);
    if (property != null) {
      final String namespace = property.getNamespace();
      final String pipelineConfigName = property.getName();

      V1alpha1PipelineConfig pipelineConfig =
          Clients.get(V1alpha1PipelineConfig.class)
              .lister()
              .namespace(namespace)
              .get(pipelineConfigName);
      if (pipelineConfig != null) {
        logger.info(() -> "Got pipeline config for  " + namespace + "/" + pipelineConfigName);

        PipelineConfigEvents.newJobDeletedEvent(
                namespace, pipelineConfigName, "Job deleted in Jenkins")
            .submit();
        Clients.get(V1alpha1PipelineConfig.class).delete(namespace, pipelineConfigName);
        logger.info(() -> "Deleting PipelineConfig " + namespace + "/" + pipelineConfigName);
      } else {
        logger.info(() -> "No pipeline config for " + namespace + "/" + pipelineConfigName);
      }
    }
  }

  /**
   * Update or insert target workflow job
   *
   * @param job target workflow job
   */
  private void upsertWorkflowJob(WorkflowJob job) {
    WorkflowJobProperty property = pipelineConfigProjectForJob(job);

    // we just take care of our style's jobs
    if (property != null && isNotDeleteInProgress(property)) {
      logger.info(
          () ->
              "Upsert WorkflowJob "
                  + job.getName()
                  + " to PipelineConfig: "
                  + property.getNamespace()
                  + "/"
                  + property.getName()
                  + " in Alauda Kubernetes");

      upsertPipelineConfigForJob(job, property);
    } else {
      logger.log(Level.FINE, "skip job {0}, it is not created by alauda", job);
    }
  }

  /**
   * Returns the mapping of the jenkins workflow job to a qualified namespace and PipelineConfig
   * name
   */
  private WorkflowJobProperty pipelineConfigProjectForJob(WorkflowJob job) {
    WorkflowJobProperty property = WorkflowJobUtils.getAlaudaProperty(job);
    if (property != null) {
      if (StringUtils.isNotBlank(property.getNamespace())
          && StringUtils.isNotBlank(property.getName())) {
        logger.info(
            "Found WorkflowJobProperty for namespace: "
                + property.getNamespace()
                + " name: "
                + property.getName());
        return property;
      }
    }
    return null;
  }

  private boolean isNotDeleteInProgress(WorkflowJobProperty property) {
    return !jenkinsClient.isDeleteInProgress(property.getNamespace(), property.getName());
  }

  private void upsertPipelineConfigForJob(
      WorkflowJob job, WorkflowJobProperty workflowJobProperty) {
    final String namespace = workflowJobProperty.getNamespace();
    final String jobName = workflowJobProperty.getName();

    V1alpha1PipelineConfig jobPipelineConfig =
        Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(jobName);
    if (jobPipelineConfig == null) {
      logger.log(
          Level.WARNING,
          String.format(
              "Unable to found mapped PipelineConfig '%s/%s' for Jenkins job %s, may have a potential bug",
              namespace, jobName, job.getFullDisplayName()));
      return;
    }

    if (!BindResourcePredicate.isBindedResource(
        namespace, jobPipelineConfig.getSpec().getJenkinsBinding().getName())) {
      logger.log(
          Level.WARNING,
          String.format(
              " PipelineConfigController: '%s/%s' is not bind to correct jenkinsbinding, will skip it",
              namespace, jobName));
      return;
    }

    // Use lock to avoid conflict between PipelineConfigController and listener
    synchronized (jobPipelineConfig.getMetadata().getUid().intern()) {
      V1ObjectMeta metadata = jobPipelineConfig.getMetadata();

      String uid = workflowJobProperty.getUid();
      if (StringUtils.isEmpty(uid)) {
        workflowJobProperty.setUid(metadata.getUid());
      } else if (!Objects.equal(uid, metadata.getUid())) {
        // the UUIDs are different so lets ignore this PipelineConfig
        logger.log(
            Level.WARNING,
            String.format(
                "PipelineConfig '%s/%s' 's UUID %s is different from local %s, will skip it",
                namespace, job, metadata.getUid(), uid));
        return;
      }

      V1alpha1PipelineConfig newJobPipelineConfig = DeepCopyUtils.deepCopy(jobPipelineConfig);
      PipelineConfigToJobMapper.updatePipelineConfigFromJob(job, newJobPipelineConfig);

      if (!hasEmbeddedPipelineOrValidSource(newJobPipelineConfig)) {
        // this pipeline has not yet been populated with the git source or
        // an embedded
        // pipeline so lets not create/update a PipelineConfig yet
        return;
      }

      PipelineConfigEvents.newJobUpdatedEvent(
              newJobPipelineConfig, "Workflow job configuration changed in Jenkins")
          .submit();

      Clients.get(V1alpha1PipelineConfig.class).update(jobPipelineConfig, newJobPipelineConfig);
    }
  }

  private boolean hasEmbeddedPipelineOrValidSource(V1alpha1PipelineConfig pipelineConfig) {
    V1alpha1PipelineConfigSpec spec = pipelineConfig.getSpec();
    if (spec != null) {
      V1alpha1PipelineStrategy strategy = spec.getStrategy();
      if (strategy != null) {

        V1alpha1PipelineStrategyJenkins jenkinsPipelineStrategy = strategy.getJenkins();
        if (jenkinsPipelineStrategy != null) {
          if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfile())) {
            return true;
          }
          if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfilePath())) {
            V1alpha1PipelineSource source = spec.getSource();
            if (source != null) {
              V1alpha1PipelineSourceGit git = source.getGit();
              if (git != null) {
                return (StringUtils.isNotBlank(git.getUri()));
              }
              // TODO support other SCMs
            }
          }
        }
      }
    }
    return false;
  }
}
