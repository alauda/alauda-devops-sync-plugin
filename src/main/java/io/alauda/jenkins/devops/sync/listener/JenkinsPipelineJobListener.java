/**
 * Copyright (C) 2018 Alauda.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.listener;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import com.google.common.base.Objects;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.listeners.ItemListener;

import io.alauda.jenkins.devops.sync.*;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.jenkins.devops.sync.watcher.PipelineConfigWatcher;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.KubernetesClientException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Collection;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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

  private String server;
  private String[] namespaces;
  private String jenkinsService;
  private String jobNamePattern;

  public JenkinsPipelineJobListener() {
    reconfigure();
//    init();
  }

  @DataBoundConstructor
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public JenkinsPipelineJobListener(String server, String jenkinsService, String jobNamePattern) {
    this.server = server;
    this.jenkinsService = jenkinsService;
    this.jobNamePattern = jobNamePattern;
    init();
  }

  @Override
  public String toString() {
    return "JenkinsPipelineJobListener{" + "server='" + server + '\''+ ", jenkinsService='" + jenkinsService+ '\'' + ", namespace='" + namespaces + '\'' + ", jobNamePattern='" + jobNamePattern + '\'' + '}';
  }

  private void init() {
    namespaces = AlaudaUtils.getNamespaceOrUseDefault(jenkinsService, AlaudaUtils.getAlaudaClient());
  }

  @Override
  public void onCreated(Item item) {
    if (!GlobalPluginConfiguration.isItEnabled())
      return;
    reconfigure();
    super.onCreated(item);
    upsertItem(item);
  }

  @Override
  public void onUpdated(Item item) {
    if (!GlobalPluginConfiguration.isItEnabled())
      return;
    reconfigure();
    super.onUpdated(item);
    upsertItem(item);
  }

  @Override
  public void onDeleted(Item item) {
    logger.info("onDelete: Item: "+item);
    if (!GlobalPluginConfiguration.isItEnabled()) {
      logger.info("no configuration... onDelete ignored...");
      return;
    }
//    reconfigure();
    super.onDeleted(item);
    if (item instanceof WorkflowJob) {
      WorkflowJob job = (WorkflowJob) item;
      PipelineConfigProjectProperty property = pipelineConfigProjectForJob(job);
      if (property != null) {

//        NamespaceName pipelineName = AlaudaUtils.pipelineConfigNameFromJenkinsJobName(job.getName(), job.getProperty(PipelineConfigProjectProperty.class).getNamespace());
        NamespaceName pipelineName = AlaudaUtils.pipelineConfigNameFromJenkinsJobName(property.getName(), property.getNamespace());
        logger.info("Got namespaceName from item. namespace: "+pipelineName.getNamespace()+" name: "+pipelineName.getName());
        String namespace = pipelineName.getNamespace();
        String pipelineConfigName = pipelineName.getName();
        PipelineConfig pipelineConfig = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().inNamespace(namespace).withName(pipelineConfigName).get();
        if (pipelineConfig != null) {
          logger.info("Got pipeline config for  "+pipelineName.getNamespace()+"/"+pipelineName.getName());
          boolean generatedBySyncPlugin = false;
          Map<String, String> annotations = pipelineConfig.getMetadata().getAnnotations();
          if (annotations != null) {
            generatedBySyncPlugin = Annotations.GENERATED_BY_JENKINS.equals(annotations.get(Annotations.GENERATED_BY));
          }
          // hacking for test
          generatedBySyncPlugin = true;
          try {
            if (!generatedBySyncPlugin) {
              logger.info("PipelineConfig " + property.getNamespace() + "/" + property.getName() + " will not" + " be deleted since it was not created by " + " the sync plugin");
            } else {
              logger.info("Deleting PipelineConfig " + property.getNamespace() + "/" + property.getName());
              AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().inNamespace(namespace).withName(pipelineConfigName).delete();

            }
          } catch (KubernetesClientException e) {
            if (HTTP_NOT_FOUND != e.getCode()) {
              logger.log(Level.WARNING, "Failed to delete PipelineConfig in namespace: " + namespace + " for name: " + pipelineConfigName, e);
            }
          } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to delete PipelineConfig in namespace: " + namespace + " for name: " + pipelineConfigName, e);
          } finally {
            PipelineConfigToJobMap.removeJobWithPipelineConfig(pipelineConfig);
          }
        } else {
          logger.info("No pipeline config for  "+pipelineName.getNamespace()+"/"+pipelineName.getName());
        }
      }
    }
    reconfigure();
  }

  public void upsertItem(Item item) {
    if (item instanceof WorkflowJob) {
      upsertWorkflowJob((WorkflowJob) item);
    } else if (item instanceof ItemGroup) {
      upsertItemGroup((ItemGroup) item);
    }
  }

  private void upsertItemGroup(ItemGroup itemGroup) {
    Collection items = itemGroup.getItems();
    if (items != null) {
      for (Object child : items) {
        if (child instanceof WorkflowJob) {
          upsertWorkflowJob((WorkflowJob) child);
        } else if (child instanceof ItemGroup) {
          upsertItemGroup((ItemGroup) child);
        }
      }
    }
  }

  private void upsertWorkflowJob(WorkflowJob job) {
    PipelineConfigProjectProperty property = pipelineConfigProjectForJob(job);
    if (property != null && (!PipelineConfigWatcher.isDeleteInProgress(property.getNamespace() + property.getName()))) {
      logger.info("Upsert WorkflowJob " + job.getName() + " to PipelineConfig: " + property.getNamespace() + "/" + property.getName() + " in Alauda Kubernetes");
      upsertPipelineConfigForJob(job, property);
    }
  }

  /**
   * Returns the mapping of the jenkins workflow job to a qualified namespace
   * and PipelineConfig name
   */
  private PipelineConfigProjectProperty pipelineConfigProjectForJob(WorkflowJob job) {

    PipelineConfigProjectProperty property = job.getProperty(PipelineConfigProjectProperty.class);

    if (property != null) {
      if (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName())) {
        logger.info("Found PipelineConfigProjectProperty for namespace: " + property.getNamespace() + " name: " + property.getName());
        return property;
      }
    }

    String patternRegex = this.jobNamePattern;
    String jobName = JenkinsUtils.getFullJobName(job);
    if (StringUtils.isNotEmpty(jobName) && StringUtils.isNotEmpty(patternRegex) && jobName.matches(patternRegex)) {
      String pipelineConfigName = AlaudaUtils.convertNameToValidResourceName(JenkinsUtils.getBuildConfigName(job));

      // we will update the uuid when we create the BC
      String uuid = null;

      // TODO what to do for the resourceVersion?
      String resourceVersion = null;
      String pipelineRunPolicy = Constants.PIPELINE_RUN_POLICY_DEFAULT;
      for (String namespace : namespaces) {
        logger.info("Creating PipelineConfigProjectProperty for namespace: " + namespace + " name: " + pipelineConfigName);
        if (property != null) {
          property.setNamespace(namespace);
          property.setName(pipelineConfigName);
          if (!StringUtils.isNotBlank(property.getPipelineRunPolicy())) {
            property.setPipelineRunPolicy(pipelineRunPolicy);
          }
          return property;
        } else {
          return new PipelineConfigProjectProperty(namespace, pipelineConfigName, uuid, resourceVersion, pipelineRunPolicy);
        }
      }

    }
    return null;
  }

  private void upsertPipelineConfigForJob(WorkflowJob job, PipelineConfigProjectProperty pipelineConfigProjectProperty) {
    boolean create = false;
    PipelineConfig jobPipelineConfig = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().inNamespace(pipelineConfigProjectProperty.getNamespace()).withName(pipelineConfigProjectProperty.getName()).get();
    if (jobPipelineConfig == null) {
      create = true;
      // TODO: Adjust this part
      jobPipelineConfig = new PipelineConfigBuilder().withNewMetadata().withName(pipelineConfigProjectProperty.getName()).withNamespace(pipelineConfigProjectProperty.getNamespace())
        .addToAnnotations(Annotations.GENERATED_BY, Annotations.GENERATED_BY_JENKINS)
        .endMetadata()
        .withNewSpec()
        .withNewStrategy()
        .withNewJenkins()
        .endJenkins()
        .endStrategy().endSpec().build();
    } else {
      ObjectMeta metadata = jobPipelineConfig.getMetadata();
      String uid = pipelineConfigProjectProperty.getUid();
      if (metadata != null && StringUtils.isEmpty(uid)) {
        pipelineConfigProjectProperty.setUid(metadata.getUid());
      } else if (metadata != null && !Objects.equal(uid, metadata.getUid())) {
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

    // lets annotate with the job name
    if (create) {
      AlaudaUtils.addAnnotation(jobPipelineConfig, Annotations.JENKINS_JOB_PATH, JenkinsUtils.getFullJobName(job));
    }

    if (create) {
      try {
        PipelineConfig pc = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().inNamespace(jobPipelineConfig.getMetadata().getNamespace()).create(jobPipelineConfig);
        String uid = pc.getMetadata().getUid();
        pipelineConfigProjectProperty.setUid(uid);
      } catch (Exception e) {
        logger.log(Level.WARNING, "Failed to create PipelineConfig: " + NamespaceName.create(jobPipelineConfig) + ". " + e, e);
      }
    } else {
      try {
        AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().inNamespace(jobPipelineConfig.getMetadata().getNamespace()).withName(jobPipelineConfig.getMetadata().getName()).cascading(false).replace(jobPipelineConfig);
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
                if (StringUtils.isNotBlank(git.getUri())) {
                  return true;
                }
              }
              // TODO support other SCMs
            }
          }
        }
      }
    }
    return false;
  }

  /**
   * TODO is there a cleaner way to get this class injected with any new
   * configuration from GlobalPluginConfiguration?
   */
  private void reconfigure() {
    GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
    if (config != null) {
      this.jobNamePattern = config.getJobNamePattern();
      this.jenkinsService = config.getJenkinsService();
      this.server = config.getServer();
      init();
    }
  }
}
