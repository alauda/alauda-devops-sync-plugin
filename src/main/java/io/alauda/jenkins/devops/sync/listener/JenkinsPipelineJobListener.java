/**
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.listener;

import com.google.common.base.Objects;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.dsl.PipelineConfigResource;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.jenkins.devops.sync.watcher.PipelineConfigWatcher;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.KubernetesClientException;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.validation.constraints.NotNull;
import java.awt.event.ItemEvent;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_BRANCH;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or
 * Jenkins REST API and replicating the changes back to the Alauda DevOps
 * {@link PipelineConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class JenkinsPipelineJobListener extends ItemListener {
    private static final Logger logger = Logger.getLogger(JenkinsPipelineJobListener.class.getName());
    private final ExtensionList<ItemEventHandler> handler;

    private String server;
    private String[] namespaces;
    private String jenkinsService;
    private String jobNamePattern;

    @DataBoundConstructor
    public JenkinsPipelineJobListener() {
        handler = Jenkins.getInstance().getExtensionList(ItemEventHandler.class);
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        this.server = config.getServer();
        this.jenkinsService = config.getJenkinsService();
        this.jobNamePattern = config.getJobNamePattern();
        init();
    }

    private void init() {
        AlaudaDevOpsClient client = AlaudaUtils.getAlaudaClient();
        if(client == null) {
            logger.severe("Can't get AlaudaDevOpsClient when init JenkinsPipelineJobListener.");
            return;
        }
        namespaces = AlaudaUtils.getNamespaceOrUseDefault(jenkinsService, client);
    }

    @Override
    public void onCreated(Item item) {
        if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
            return;
        }

        logger.info(String.format("created item: %s", item.getFullName()));

        handler.stream().
                filter(handler -> handler.accept(item)).
                forEach(handler -> handler.onCreated(item));
        reconfigure();
    }

    @Override
    public void onUpdated(Item item) {
        if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
            return;
        }

        logger.info(String.format("updated item: %s", item.getFullName()));

        handler.stream().
                filter(handler -> handler.accept(item)).
                forEach(handler -> handler.onUpdated(item));
        reconfigure();
    }

    @Override
    public void onDeleted(Item item) {
        if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
            logger.info("no configuration... onDelete ignored...");
            return;
        }

        logger.info("deleted Item: %s" + item.getFullName());

        handler.stream().
                filter(handler -> handler.accept(item)).
                forEach(handler -> handler.onDeleted(item));
        reconfigure();
    }
//
//    /**
//     * Create or update the pipeline job
//     * @param item
//     */
//    public void upsertItem(Item item) {
//        if (item instanceof WorkflowJob) {
//            upsertWorkflowJob((WorkflowJob) item);
//        } else if (item instanceof ItemGroup) {
//            upsertItemGroup((ItemGroup) item);
//        }
//    }
//
//    private void upsertItemGroup(ItemGroup itemGroup) {
//        Collection items = itemGroup.getItems();
//        if (items != null) {
//            for (Object child : items) {
//                if (child instanceof WorkflowJob) {
//                    upsertWorkflowJob((WorkflowJob) child);
//                } else if (child instanceof ItemGroup) {
//                    upsertItemGroup((ItemGroup) child);
//                }
//            }
//        }
//    }
//
//    /**
//     * Update or insert target workflow job
//     * @param job target workflow job
//     */
//    private void upsertWorkflowJob(WorkflowJob job) {
//        WorkflowJobProperty property = pipelineConfigProjectForJob(job);
//
//        //we just take care of our style's jobs
//        if (canUpdate(job, property)) {
//            logger.info(() -> "Upsert WorkflowJob " + job.getName() + " to PipelineConfig: "
//                    + property.getNamespace() + "/" + property.getName() + " in Alauda Kubernetes");
//
//            upsertPipelineConfigForJob(job, property);
//        }
//    }
//
//    /**
//     * Check status of job
//     * @param job WorkflowJob
//     * @param property WorkflowJobProperty
//     * @return if we can update the job, will return true
//     */
//    private boolean canUpdate(WorkflowJob job, WorkflowJobProperty property) {
//        return (property != null && isNotDeleteInProgress(property));
//    }
//
//    private boolean isNotDeleteInProgress(WorkflowJobProperty property) {
//        return !PipelineConfigWatcher.isDeleteInProgress(property.getNamespace() + property.getName());
//    }
//
//    /**
//     * Returns the mapping of the jenkins workflow job to a qualified namespace
//     * and PipelineConfig name
//     */
//    private WorkflowJobProperty pipelineConfigProjectForJob(WorkflowJob job) {
//        WorkflowJobProperty property = job.getProperty(WorkflowJobProperty.class);
//
//        if (property != null) {
//            if (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName())) {
//                logger.info("Found WorkflowJobProperty for namespace: " + property.getNamespace() + " name: " + property.getName());
//                return property;
//            }
//        }
//
////        String patternRegex = this.jobNamePattern;
////        String jobName = JenkinsUtils.getFullJobName(job);
////        if (StringUtils.isNotEmpty(jobName) && StringUtils.isNotEmpty(patternRegex) && jobName.matches(patternRegex)) {
////            String pipelineConfigName = AlaudaUtils.convertNameToValidResourceName(JenkinsUtils.getBuildConfigName(job));
////
////            // we will update the uuid when we create the BC
////            String uuid = null;
////
////            // TODO what to do for the resourceVersion?
////            String resourceVersion = null;
////            String pipelineRunPolicy = Constants.PIPELINE_RUN_POLICY_DEFAULT;
////            for (String namespace : namespaces) {
////                logger.info("Creating WorkflowJobProperty for namespace: " + namespace + " name: " + pipelineConfigName);
////                if (property != null) {
////                    property.setNamespace(namespace);
////                    property.setName(pipelineConfigName);
////                    return property;
////                } else {
////                    return new WorkflowJobProperty(namespace, pipelineConfigName, uuid, resourceVersion);
////                }
////            }
////
////        }
//        return null;
//    }
//
//    private void upsertPipelineConfigForJob(WorkflowJob job, WorkflowJobProperty workflowJobProperty) {
//        boolean create = false;
//        final AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
//        final String namespace = workflowJobProperty.getNamespace();
//        final String jobName = workflowJobProperty.getName();
//        if(client == null) {
//            logger.warning("Can't get kubernetes client in method upsertPipelineConfigForJob.");
//            return;
//        }
//
//        PipelineConfigResource<PipelineConfig, DoneablePipelineConfig, Void, Pipeline> pipelineConfigResource =
//                client.pipelineConfigs().inNamespace(namespace).withName(jobName);
//
//        PipelineConfig jobPipelineConfig = pipelineConfigResource.get();
//        if (jobPipelineConfig == null) {
//            Namespace targetNS = client.namespaces().withName(namespace).get();
//            String msg = String.format("There's not namespace with name: %s. Can't create PipelineConfig.", namespace);
//            if(targetNS == null) {
//                logger.severe(msg);
//                return;
//            }
//
//            logger.info("Can't find PipelineConfig, will create. namespace:" + namespace + "; name: " + jobName);
//
//            create = true;
//            // TODO: Adjust this part
//            jobPipelineConfig = new PipelineConfigBuilder().withNewMetadata().withName(jobName)
//                    .withNamespace(namespace)
//                    .addToAnnotations(Annotations.GENERATED_BY, Annotations.GENERATED_BY_JENKINS)
//                    .endMetadata().withNewSpec()
//                    .withNewStrategy().withNewJenkins().endJenkins()
//                    .endStrategy().endSpec().build();
//        } else {
//            ObjectMeta metadata = jobPipelineConfig.getMetadata();
//            if (metadata == null) {
//                logger.warning("PipelineConfig's metadata is missing.");
//                return;
//            }
//
//            String uid = workflowJobProperty.getUid();
//            if (StringUtils.isEmpty(uid)) {
//                workflowJobProperty.setUid(metadata.getUid());
//            } else if (!Objects.equal(uid, metadata.getUid())) {
//                // the UUIDs are different so lets ignore this PipelineConfig
//                return;
//            }
//        }
//
//        PipelineConfigToJobMapper.updatePipelineConfigFromJob(job, jobPipelineConfig);
//
//        if (!hasEmbeddedPipelineOrValidSource(jobPipelineConfig)) {
//            // this pipeline has not yet been populated with the git source or
//            // an embedded
//            // pipeline so lets not create/update a PipelineConfig yet
//            return;
//        }
//
//        if (create) {
//            AlaudaUtils.addAnnotation(jobPipelineConfig, Annotations.JENKINS_JOB_PATH, JenkinsUtils.getFullJobName(job));
//
//            try {
//                PipelineConfig pc = client.pipelineConfigs().inNamespace(jobPipelineConfig.getMetadata().getNamespace()).
//                        create(jobPipelineConfig);
//                String uid = pc.getMetadata().getUid();
//                workflowJobProperty.setUid(uid);
//            } catch (Exception e) {
//                logger.log(Level.WARNING, "Failed to create PipelineConfig: " + NamespaceName.create(jobPipelineConfig) + ". " + e, e);
//            }
//        } else {
//            try {
//                PipelineConfigSpec spec = jobPipelineConfig.getSpec();
//
//                client.pipelineConfigs().
//                        inNamespace(namespace).withName(jobName).edit().editOrNewSpec()
//                        .withTriggers(spec.getTriggers())
//                        .withParameters(spec.getParameters()).
//                        withSource(spec.getSource()).withStrategy(spec.getStrategy()).endSpec().
//                        done();
//                logger.info("PipelineConfig update success, " + jobName);
//            } catch (Exception e) {
//                logger.log(Level.WARNING, "Failed to update PipelineConfig: " + NamespaceName.create(jobPipelineConfig) + ". " + e, e);
//            }
//        }
//    }
//
//    private boolean hasEmbeddedPipelineOrValidSource(PipelineConfig pipelineConfig) {
//        PipelineConfigSpec spec = pipelineConfig.getSpec();
//        if (spec != null) {
//            PipelineStrategy strategy = spec.getStrategy();
//            if (strategy != null) {
//
//                PipelineStrategyJenkins jenkinsPipelineStrategy = strategy.getJenkins();
//                if (jenkinsPipelineStrategy != null) {
//                    if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfile())) {
//                        return true;
//                    }
//                    if (StringUtils.isNotBlank(jenkinsPipelineStrategy.getJenkinsfilePath())) {
//                        PipelineSource source = spec.getSource();
//                        if (source != null) {
//                            PipelineSourceGit git = source.getGit();
//                            if (git != null) {
//                                return (StringUtils.isNotBlank(git.getUri()));
//                            }
//                            // TODO support other SCMs
//                        }
//                    }
//                }
//            }
//        }
//        return false;
//    }

    /**
     * TODO is there a cleaner way to get this class injected with any new
     * configuration from GlobalPluginConfiguration?
     */
    private void reconfigure() {
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        if (config != null) {
            this.jobNamePattern = config.getJobNamePattern();
            this.jenkinsService = config.getJenkinsService();
            this.server = config.getServer();
            init();
        }
    }

    @Override
    public String toString() {
        return "JenkinsPipelineJobListener{" + "server='" + server + '\'' + ", jenkinsService='" + jenkinsService + '\'' + ", namespace='" + Arrays.toString(namespaces) + '\'' + ", jobNamePattern='" + jobNamePattern + '\'' + '}';
    }
}
