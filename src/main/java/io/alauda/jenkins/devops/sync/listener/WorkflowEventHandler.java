package io.alauda.jenkins.devops.sync.listener;

import com.google.common.base.Objects;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.*;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.controller.JenkinsBindingController;
import io.alauda.jenkins.devops.sync.controller.PipelineConfigController;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1ObjectMetaBuilder;
import io.kubernetes.client.models.V1Status;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class WorkflowEventHandler implements ItemEventHandler<WorkflowJob> {
    private static final Logger logger = Logger.getLogger(WorkflowEventHandler.class.getName());

    @Override
    public boolean accept(Item item) {
        if(!(item instanceof WorkflowJob)) {
            return false;
        }

        ItemGroup<? extends Item> parent = item.getParent();
        return !(parent instanceof WorkflowMultiBranchProject);
    }

    @Override
    public void onCreated(WorkflowJob item){
        upsertWorkflowJob(item);
    }

    @Override
    public void onUpdated(WorkflowJob item) {
        upsertWorkflowJob(item);
    }

    @Override
    public void onDeleted(WorkflowJob item) {
        WorkflowJobProperty property = pipelineConfigProjectForJob(item);
        if (property != null) {
            final String namespace = property.getNamespace();
            final String pipelineConfigName = property.getName();

            V1alpha1PipelineConfig pipelineConfig = PipelineConfigController.getCurrentPipelineConfigController().getPipelineConfig(namespace, pipelineConfigName);
            if (pipelineConfig != null) {
                logger.info(() -> "Got pipeline config for  " + namespace + "/" + pipelineConfigName);

                try {
                    V1Status result = PipelineConfigController.deletePipelineConfig(namespace, pipelineConfigName);

                    logger.info(() -> "Deleting PipelineConfig " + namespace + "/" + pipelineConfigName);
                } finally {
                    PipelineConfigToJobMap.removeJobWithPipelineConfig(pipelineConfig);
                }
            } else {
                logger.info(() -> "No pipeline config for " + namespace + "/" + pipelineConfigName);
            }
        }
    }

    /**
     * Update or insert target workflow job
     * @param job target workflow job
     */
    private void upsertWorkflowJob(WorkflowJob job) {
        WorkflowJobProperty property = pipelineConfigProjectForJob(job);

        //we just take care of our style's jobs
        if (canUpdate(job, property)) {
            logger.info(() -> "Upsert WorkflowJob " + job.getName() + " to PipelineConfig: "
                    + property.getNamespace() + "/" + property.getName() + " in Alauda Kubernetes");

            upsertPipelineConfigForJob(job, property);
        }
    }

    /**
     * Returns the mapping of the jenkins workflow job to a qualified namespace
     * and PipelineConfig name
     */
    private WorkflowJobProperty pipelineConfigProjectForJob(WorkflowJob job) {
        WorkflowJobProperty property = WorkflowJobUtils.getAlaudaProperty(job);
        if (property != null) {
            if (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName())) {
                logger.info("Found WorkflowJobProperty for namespace: " + property.getNamespace() + " name: " + property.getName());
                return property;
            }
        }
        return null;
    }

    /**
     * Check status of job
     * @param job WorkflowJob
     * @param property WorkflowJobProperty
     * @return if we can update the job, will return true
     */
    private boolean canUpdate(WorkflowJob job, WorkflowJobProperty property) {
        return (property != null && isNotDeleteInProgress(property));
    }

    private boolean isNotDeleteInProgress(WorkflowJobProperty property) {
        return !PipelineConfigController.isDeleteInProgress(property.getNamespace() + property.getName());
    }

    private void upsertPipelineConfigForJob(WorkflowJob job, WorkflowJobProperty workflowJobProperty) {
        boolean create = false;
        final String namespace = workflowJobProperty.getNamespace();
        final String jobName = workflowJobProperty.getName();

        V1alpha1PipelineConfig jobPipelineConfig = PipelineConfigController.getCurrentPipelineConfigController().getPipelineConfig(namespace, jobName);
        if (jobPipelineConfig == null) {
            boolean hasMappedNs = JenkinsBindingController.getCurrentJenkinsBindingController().getBindingNamespaces().contains(namespace);
            String msg = String.format("There's not namespace with name: %s. Can't create PipelineConfig.", namespace);
            if(!hasMappedNs) {
                logger.severe(msg);
                return;
            }

            logger.info("Can't find PipelineConfig, will create. namespace:" + namespace + "; name: " + jobName);

            create = true;
            // TODO: Adjust this part
            jobPipelineConfig = new V1alpha1PipelineConfigBuilder().withMetadata(new V1ObjectMetaBuilder().withName(jobName)
                    .withNamespace(namespace)
                    .addToAnnotations(Annotations.GENERATED_BY, Annotations.GENERATED_BY_JENKINS)
                    .build()).withNewSpec()
                    .withNewStrategy().withNewJenkins().endJenkins()
                    .endStrategy().endSpec().build();
        } else {
            V1ObjectMeta metadata = jobPipelineConfig.getMetadata();
            if (metadata == null) {
                logger.warning("PipelineConfig's metadata is missing.");
                return;
            }

            String uid = workflowJobProperty.getUid();
            if (StringUtils.isEmpty(uid)) {
                workflowJobProperty.setUid(metadata.getUid());
            } else if (!Objects.equal(uid, metadata.getUid())) {
                // the UUIDs are different so lets ignore this PipelineConfig
                return;
            }
        }

        V1alpha1PipelineConfig newJobPipelineConfig = DeepCopyUtils.deepCopy(jobPipelineConfig);
        PipelineConfigToJobMapper.updatePipelineConfigFromJob(job, newJobPipelineConfig);

        if (!hasEmbeddedPipelineOrValidSource(newJobPipelineConfig)) {
            // this pipeline has not yet been populated with the git source or
            // an embedded
            // pipeline so lets not create/update a PipelineConfig yet
            return;
        }

        if (create) {
            newJobPipelineConfig.getMetadata().getAnnotations().put(Annotations.JENKINS_JOB_PATH, JenkinsUtils.getFullJobName(job));
            try {
                V1alpha1PipelineConfig pc = PipelineConfigController.createPipelineConfig(namespace, newJobPipelineConfig);
                String uid = pc.getMetadata().getUid();
                workflowJobProperty.setUid(uid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to create PipelineConfig: " + namespace + jobName + ". " + e, e);
            }
        } else {
            try {
                V1alpha1PipelineConfigSpec spec = newJobPipelineConfig.getSpec();

                PipelineConfigController.updatePipelineConfig(jobPipelineConfig, newJobPipelineConfig);
                logger.info("PipelineConfig update success, " + jobName);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update PipelineConfig: " + namespace + jobName + ". " + e, e);
            }
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
