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
package io.alauda.jenkins.devops.sync;

import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.scm.SCM;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.*;

import jenkins.branch.Branch;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.util.CredentialsUtils.updateSourceCredentials;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class PipelineConfigToJobMapper {
  public static final String JENKINS_PIPELINE_BUILD_STRATEGY = "JenkinsPipeline";
  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";
  private static final Logger LOGGER = Logger.getLogger(PipelineConfigToJobMapper.class.getName());

  public static FlowDefinition mapPipelineConfigToFlow(PipelineConfig pc) throws IOException {
    if (!AlaudaUtils.isPipelineStrategyPipelineConfig(pc)) {
      return null;
    }

    PipelineConfigSpec spec = pc.getSpec();
    PipelineSource source = null;
    String jenkinsfile = null;
    String jenkinsfilePath = null;
    if (spec != null) {
      PipelineStrategy strategy = spec.getStrategy();
      source = spec.getSource();
      if (strategy != null) {
        PipelineStrategyJenkins jenkinsPipelineStrategy = strategy.getJenkins();
        if (jenkinsPipelineStrategy != null) {
          jenkinsfile = jenkinsPipelineStrategy.getJenkinsfile();
          jenkinsfilePath = jenkinsPipelineStrategy.getJenkinsfilePath();
        }
      }
    }
    if (jenkinsfile == null) {
      // Is this a Jenkinsfile from Git SCM?
      if (source != null && source.getGit() != null && source.getGit().getUri() != null) {
        if (jenkinsfilePath == null) {
          jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
        }
        // TODO: Not sure what this thing does
//        if (!isEmpty(source.getContextDir())) {
//          jenkinsfilePath = new File(source.getContextDir(), jenkinsfilePath).getPath();
//        }
        PipelineSourceGit gitSource = source.getGit();
        String branchRef = gitSource.getRef();
        List<BranchSpec> branchSpecs = Collections.emptyList();
        if (isNotBlank(branchRef)) {
          branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
        }

        String credentialsId = updateSourceCredentials(pc);
        // if credentialsID is null, go with an SCM where anonymous has to be sufficient
        GitSCM scm = new GitSCM(
          Collections.singletonList(new UserRemoteConfig(gitSource.getUri(), null, null, credentialsId)),
          branchSpecs, false, Collections.<SubmoduleConfig>emptyList(), null, null,
          Collections.<GitSCMExtension>emptyList());
        return new CpsScmFlowDefinition(scm, jenkinsfilePath);
      } else {
        LOGGER.warning("PipelineConfig does not contain source repository information - "
          + "cannot map PipelineConfig to Jenkins job");
        return null;
      }
    } else {
      return new CpsFlowDefinition(jenkinsfile, true);
    }
  }

  /**
   * Find PipelineTrigger according to the type
   * @param pipelineConfig PipelineConfig
   * @param type type of PipelineTrigger
   * @return target PipelineTrigger. Return null if can not find it.
   */
  private static PipelineTrigger findPipelineTriggers(PipelineConfig pipelineConfig, String type) {
    List<PipelineTrigger> triggers = pipelineConfig.getSpec().getTriggers();
    if(triggers == null) {
      return null;
    }

    for(PipelineTrigger trigger : triggers) {
      if(trigger.getType().equals(type)) {
        return trigger;
      }
    }

    return null;
  }

  /**
   * Update triggers to k8s  resources. We support scm and cron for now.
   * @param job WorkflowJob
   * @param pipelineConfig PipelineConfig
   */
  private static void updateTrigger(WorkflowJob job, PipelineConfig pipelineConfig) {
    // checking if there are triggers to be updated
    if ((job.getTriggers() != null && job.getTriggers().size() >0 )
            || (pipelineConfig.getSpec().getTriggers() != null && pipelineConfig.getSpec().getTriggers().size() > 0)) {
      for (Trigger<?> trigger : job.getTriggers().values()) {
        if(trigger instanceof SCMTrigger) {
          PipelineTrigger pipelineTrigger = findPipelineTriggers(pipelineConfig,
                  Constants.PIPELINE_TRIGGER_TYPE_CODE_CHANGE);
          SCMTrigger scmTrigger = (SCMTrigger) trigger;

          if(pipelineTrigger == null) {
            pipelineTrigger = new PipelineTriggerBuilder()
                    .withType(Constants.PIPELINE_TRIGGER_TYPE_CODE_CHANGE)
                    .withNewCodeChange()
                    .withEnabled(true)
                    .withPeriodicCheck(scmTrigger.getSpec())
                    .endCodeChange().build();
            pipelineConfig.getSpec().getTriggers().add(pipelineTrigger);
          } else {
            pipelineTrigger.getCodeChange().setPeriodicCheck(scmTrigger.getSpec());
          }
        } else if(trigger instanceof TimerTrigger) {
          PipelineTrigger pipelineTrigger = findPipelineTriggers(pipelineConfig,
                  Constants.PIPELINE_TRIGGER_TYPE_CRON);
          TimerTrigger timerTrigger = (TimerTrigger) trigger;

          if(pipelineTrigger == null) {
            pipelineTrigger = new PipelineTriggerBuilder()
                    .withType(Constants.PIPELINE_TRIGGER_TYPE_CRON)
                    .withNewCron(true, timerTrigger.getSpec()).build();
            pipelineConfig.getSpec().getTriggers().add(pipelineTrigger);
          } else {
            pipelineTrigger.getCron().setRule(timerTrigger.getSpec());
          }
        } else {
          LOGGER.warning(() -> "Not support trigger type : " + trigger.getClass());
        }
      }
    }
  }

  /**
   * Updates the {@link PipelineConfig} if the Jenkins {@link WorkflowJob} changes
   *
   * @param job
   *            the job thats been updated via Jenkins
   * @param pipelineConfig
   *            the Alauda DevOps PipelineConfig to update
   * @return true if the PipelineConfig was changed
   */
  public static boolean updatePipelineConfigFromJob(WorkflowJob job, PipelineConfig pipelineConfig) {
    NamespaceName namespaceName = NamespaceName.create(pipelineConfig);
    PipelineStrategyJenkins pipelineStrategyJenkins = null;
    PipelineConfigSpec spec = pipelineConfig.getSpec();
    if (spec != null) {
      PipelineStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        pipelineStrategyJenkins = strategy.getJenkins();
      } else {
        LOGGER.warning(() -> "No available JenkinsPipelineStrategy in the PipelineConfig " + namespaceName);
        return false;
      }
    }

    // checking if there are triggers to be updated
    updateTrigger(job, pipelineConfig);

    FlowDefinition definition = job.getDefinition();
    if (definition instanceof CpsScmFlowDefinition) {
      CpsScmFlowDefinition cpsScmFlowDefinition = (CpsScmFlowDefinition) definition;
      String scriptPath = cpsScmFlowDefinition.getScriptPath();
      if (scriptPath != null && scriptPath.trim().length() > 0) {
        boolean rc = false;
        PipelineSource source = getOrCreatePipelineSource(spec);
//        String bcContextDir = "";
//        if (StringUtils.isNotBlank(bcContextDir) && scriptPath.startsWith(bcContextDir)) {
//          scriptPath = scriptPath.replaceFirst("^" + bcContextDir + "/?", "");
//        }

        if (!scriptPath.equals(pipelineStrategyJenkins.getJenkinsfilePath())) {
          LOGGER.log(Level.FINE,
            "updating PipelineConfig " + namespaceName + " jenkinsfile path to " + scriptPath + " from ");
          rc = true;
          pipelineStrategyJenkins.setJenkinsfilePath(scriptPath);
        }

        SCM scm = cpsScmFlowDefinition.getScm();
        if (scm instanceof GitSCM) {
          populateFromGitSCM(pipelineConfig, source, (GitSCM) scm, null);
          LOGGER.log(Level.FINE, "updating bc " + namespaceName);
          rc = true;
        }
        return rc;
      }
      return false;
    }

    if (definition instanceof CpsFlowDefinition) {
      CpsFlowDefinition cpsFlowDefinition = (CpsFlowDefinition) definition;
      String jenkinsfile = cpsFlowDefinition.getScript();
      if (jenkinsfile != null && jenkinsfile.trim().length() > 0
        && !jenkinsfile.equals(pipelineStrategyJenkins.getJenkinsfile())) {
        LOGGER.log(Level.FINE, "updating PipelineConfig " + namespaceName + " jenkinsfile to " + jenkinsfile
          + " where old jenkinsfile was " + pipelineStrategyJenkins.getJenkinsfile());
        pipelineStrategyJenkins.setJenkinsfile(jenkinsfile);
        return true;
      }

      return false;
    }

    // support multi-branch or github organization jobs
    BranchJobProperty property = job.getProperty(BranchJobProperty.class);
    if (property != null) {
      Branch branch = property.getBranch();
      if (branch != null) {
        String ref = branch.getName();
        SCM scm = branch.getScm();
        PipelineSource source = getOrCreatePipelineSource(spec);
        if (scm instanceof GitSCM) {
          if (populateFromGitSCM(pipelineConfig, source, (GitSCM) scm, ref)) {
            if (StringUtils.isEmpty(pipelineStrategyJenkins.getJenkinsfilePath())) {
              pipelineStrategyJenkins.setJenkinsfilePath("Jenkinsfile");
            }
            return true;
          }
        }
      }
    }

    LOGGER.warning("Cannot update PipelineConfig " + namespaceName + " as the definition is of class "
      + (definition == null ? "null" : definition.getClass().getName()));
    return false;
  }

  private static boolean populateFromGitSCM(PipelineConfig pipelineConfig, PipelineSource source, GitSCM gitSCM, String ref) {
    if (source.getGit() == null) {
      source.setGit(new PipelineSourceGit());
    }

    List<RemoteConfig> repositories = gitSCM.getRepositories();
    if (repositories != null && repositories.size() > 0) {
      RemoteConfig remoteConfig = repositories.get(0);
      List<URIish> urIs = remoteConfig.getURIs();
      if (urIs != null && urIs.size() > 0) {
        URIish urIish = urIs.get(0);
        String gitUrl = urIish.toString();
        if (gitUrl != null && gitUrl.length() > 0) {
          if (StringUtils.isEmpty(ref)) {
            List<BranchSpec> branches = gitSCM.getBranches();
            if (branches != null && branches.size() > 0) {
              BranchSpec branchSpec = branches.get(0);
              String branch = branchSpec.getName();
              while (branch.startsWith("*") || branch.startsWith("/")) {
                branch = branch.substring(1);
              }
              if (!branch.isEmpty()) {
                ref = branch;
              }
            }
          }
          AlaudaUtils.updateGitSourceUrl(pipelineConfig, gitUrl, ref);
          return true;
        }
      }
    }
    return false;
  }

  private static PipelineSource getOrCreatePipelineSource(PipelineConfigSpec spec) {
    PipelineSource source = spec.getSource();
    if (source == null) {
      source = new PipelineSource();
      spec.setSource(source);
    }
    return source;
  }
}
