package io.alauda.jenkins.devops.sync.listener;

import com.google.common.base.Objects;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.dsl.PipelineConfigResource;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.jenkins.devops.sync.watcher.PipelineConfigWatcher;
import io.alauda.kubernetes.api.model.DoneablePipelineConfig;
import io.alauda.kubernetes.api.model.Namespace;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigBuilder;
import io.alauda.kubernetes.api.model.PipelineConfigSpec;
import io.alauda.kubernetes.api.model.PipelineSource;
import io.alauda.kubernetes.api.model.PipelineSourceGit;
import io.alauda.kubernetes.api.model.PipelineStrategy;
import io.alauda.kubernetes.api.model.PipelineStrategyJenkins;
import io.alauda.kubernetes.client.KubernetesClientException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.util.Collection;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

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
        final AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.severe("alauda client is null, stop from onDeleted.");
            return;
        }

        WorkflowJobProperty property = pipelineConfigProjectForJob(item);
        if (property != null) {
            final String namespace = property.getNamespace();
            final String pipelineConfigName = property.getName();

            PipelineConfig pipelineConfig = client.pipelineConfigs()
                    .inNamespace(namespace).withName(pipelineConfigName).get();
            if (pipelineConfig != null) {
                logger.info(() -> "Got pipeline config for  " + namespace + "/" + pipelineConfigName);

                try {
                    client.pipelineConfigs().inNamespace(namespace).withName(pipelineConfigName).delete();

                    logger.info(() -> "Deleting PipelineConfig " + namespace + "/" + pipelineConfigName);
                } catch (KubernetesClientException e) {
                    if (HTTP_NOT_FOUND != e.getCode()) {
                        logger.log(Level.WARNING, "Failed to delete PipelineConfig in namespace: "
                                + namespace + " for name: " + pipelineConfigName, e);
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "Failed to delete PipelineConfig in namespace: "
                            + namespace + " for name: " + pipelineConfigName, e);
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
        WorkflowJobProperty property = job.getProperty(WorkflowJobProperty.class);

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
        return !PipelineConfigWatcher.isDeleteInProgress(property.getNamespace() + property.getName());
    }

    private void upsertPipelineConfigForJob(WorkflowJob job, WorkflowJobProperty workflowJobProperty) {
        boolean create = false;
        final AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        final String namespace = workflowJobProperty.getNamespace();
        final String jobName = workflowJobProperty.getName();
        if(client == null) {
            logger.warning("Can't get kubernetes client in method upsertPipelineConfigForJob.");
            return;
        }

        PipelineConfigResource<PipelineConfig, DoneablePipelineConfig, Void, Pipeline> pipelineConfigResource =
                client.pipelineConfigs().inNamespace(namespace).withName(jobName);

        PipelineConfig jobPipelineConfig = pipelineConfigResource.get();
        if (jobPipelineConfig == null) {
            Namespace targetNS = client.namespaces().withName(namespace).get();
            String msg = String.format("There's not namespace with name: %s. Can't create PipelineConfig.", namespace);
            if(targetNS == null) {
                logger.severe(msg);
                return;
            }

            logger.info("Can't find PipelineConfig, will create. namespace:" + namespace + "; name: " + jobName);

            create = true;
            // TODO: Adjust this part
            jobPipelineConfig = new PipelineConfigBuilder().withNewMetadata().withName(jobName)
                    .withNamespace(namespace)
                    .addToAnnotations(Annotations.GENERATED_BY, Annotations.GENERATED_BY_JENKINS)
                    .endMetadata().withNewSpec()
                    .withNewStrategy().withNewJenkins().endJenkins()
                    .endStrategy().endSpec().build();
        } else {
            ObjectMeta metadata = jobPipelineConfig.getMetadata();
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

        PipelineConfigToJobMapper.updatePipelineConfigFromJob(job, jobPipelineConfig);

        if (!hasEmbeddedPipelineOrValidSource(jobPipelineConfig)) {
            // this pipeline has not yet been populated with the git source or
            // an embedded
            // pipeline so lets not create/update a PipelineConfig yet
            return;
        }

        if (create) {
            AlaudaUtils.addAnnotation(jobPipelineConfig, Annotations.JENKINS_JOB_PATH, JenkinsUtils.getFullJobName(job));

            try {
                PipelineConfig pc = client.pipelineConfigs().inNamespace(jobPipelineConfig.getMetadata().getNamespace()).
                        create(jobPipelineConfig);
                String uid = pc.getMetadata().getUid();
                workflowJobProperty.setUid(uid);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to create PipelineConfig: " + NamespaceName.create(jobPipelineConfig) + ". " + e, e);
            }
        } else {
            try {
                PipelineConfigSpec spec = jobPipelineConfig.getSpec();

                client.pipelineConfigs().
                        inNamespace(namespace).withName(jobName).edit().editOrNewSpec()
                        .withTriggers(spec.getTriggers())
                        .withParameters(spec.getParameters()).
                        withSource(spec.getSource()).withStrategy(spec.getStrategy()).endSpec().
                        done();
                logger.info("PipelineConfig update success, " + jobName);
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to update PipelineConfig: " + NamespaceName.create(jobPipelineConfig) + ". " + e, e);
            }
        }
    }

    private boolean hasEmbeddedPipelineOrValidSource(PipelineConfig pipelineConfig) {
        PipelineConfigSpec spec = pipelineConfig.getSpec();
        if (spec != null) {
            PipelineStrategy strategy = spec.getStrategy();
            if (strategy != null) {

                PipelineStrategyJenkins jenkinsPipelineStrategy = strategy.getJenkins();
                if (jenkinsPipelineStrategy != null) {
                    if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfile())) {
                        return true;
                    }
                    if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfilePath())) {
                        PipelineSource source = spec.getSource();
                        if (source != null) {
                            PipelineSourceGit git = source.getGit();
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
