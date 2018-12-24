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
package io.alauda.jenkins.devops.sync;

import hudson.model.*;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.scm.SCM;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;
import io.alauda.jenkins.devops.sync.core.InvalidSecretException;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
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

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.DEFAULT_JENKINS_FILEPATH;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_TRIGGER_TYPE_CRON;
import static io.alauda.jenkins.devops.sync.util.CredentialsUtils.updateSourceCredentials;
import static org.apache.commons.lang.StringUtils.isNotBlank;
public abstract class PipelineConfigToJobMapper {
    private static final Logger LOGGER = Logger.getLogger(PipelineConfigToJobMapper.class.getName());

    private PipelineConfigToJobMapper(){}

    /**
     * Create FlowDefinition according PipelineConfig.
     * Any error will be put into conditions.
     * @param pc PipelineConfig
     * @return pipeline object
     * @throws IOException in case of io exception
     */
    public static FlowDefinition mapPipelineConfigToFlow(PipelineConfig pc) throws IOException {
        if (!AlaudaUtils.isPipelineStrategyPipelineConfig(pc)) {
            return null;
        }

        PipelineConfigSpec spec = pc.getSpec();
        PipelineSource source = null;
        String jenkinsfile = null;
        String jenkinsfilePath = null;
        if (spec != null) {
            source = spec.getSource();
            PipelineStrategy strategy = spec.getStrategy();
            if (strategy != null) {
                PipelineStrategyJenkins pipelineStrategyJenkins = strategy.getJenkins();
                if (pipelineStrategyJenkins != null) {
                    jenkinsfile = pipelineStrategyJenkins.getJenkinsfile();
                    jenkinsfilePath = pipelineStrategyJenkins.getJenkinsfilePath();
                }
            }
        }

        if (StringUtils.isBlank(jenkinsfile)) {
            // Is this a Jenkinsfile from Git SCM?
            if (source != null && isValidSource(source)) { // check null just for sonar rules
                if (jenkinsfilePath == null) {
                    jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
                }

                PipelineSourceGit gitSource = source.getGit();
                String branchRef = gitSource.getRef();
                List<BranchSpec> branchSpecs = Collections.emptyList();
                if (isNotBlank(branchRef)) {
                    branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
                }

                String credentialsId = null;
                try {
                    credentialsId = updateSourceCredentials(pc);
                } catch (InvalidSecretException e) {
                    Condition condition = new Condition();
                    condition.setReason(ErrorMessages.INVALID_CREDENTIAL);
                    condition.setMessage(e.getMessage());
                    pc.getStatus().getConditions().add(condition);
                }

                // if credentialsID is null, go with an SCM where anonymous has to be sufficient
                List<UserRemoteConfig> configs = Collections.singletonList(new UserRemoteConfig(gitSource.getUri(), null, null, credentialsId));

                GitSCM scm = new GitSCM(configs, branchSpecs, false, Collections.<SubmoduleConfig>emptyList(), null, null, Collections.<GitSCMExtension>emptyList());
                return new CpsScmFlowDefinition(scm, jenkinsfilePath);
            } else {
                Condition condition = new Condition();
                condition.setReason(ErrorMessages.INVALID_SOURCE);
                condition.setMessage("please check git uri");
                pc.getStatus().getConditions().add(condition);

                LOGGER.warning("PipelineConfig does not contain source repository information - " + "cannot map PipelineConfig to Jenkins job");
                return null;
            }
        } else {
            return new CpsFlowDefinition(jenkinsfile, true);
        }
    }

    private static boolean isValidSource(PipelineSource source) {
        return (source != null && source.getGit() != null && source.getGit().getUri() != null);
    }

    /**
     * Find PipelineTrigger according to the type
     * @param pipelineConfig PipelineConfig
     * @param type type of PipelineTrigger
     * @return target PipelineTrigger. Return null if can not find it.
     */
    private static PipelineTrigger findPipelineTriggers(@Nonnull PipelineConfig pipelineConfig, @Nonnull String type) {
        List<PipelineTrigger> triggers = pipelineConfig.getSpec().getTriggers();
        if (triggers == null) {
            return null;
        }

        for (PipelineTrigger trigger : triggers) {
            if (trigger.getType().equals(type)) {
                return trigger;
            }
        }

        return null;
    }

    /**
     * Update triggers to k8s  resources. We support scm and cron for now.
     *
     * @param job            WorkflowJob
     * @param pipelineConfig PipelineConfig
     */
    private static void updateTrigger(WorkflowJob job, PipelineConfig pipelineConfig) {
        Map<TriggerDescriptor, Trigger<?>> triggers = job.getTriggers();
        if (triggers == null) {
            return;
        }

        // checking if there are triggers to be updated
        List<PipelineTrigger> pipelineConfigTriggers = pipelineConfig.getSpec().getTriggers();
        final PipelineTrigger cronTrigger;
        if(pipelineConfigTriggers == null) {
            pipelineConfig.getSpec().setTriggers(new ArrayList<>());
            cronTrigger = null;
        } else {
            pipelineConfigTriggers.clear();
            Optional<PipelineTrigger> triggerOptional = pipelineConfigTriggers.stream().filter(trigger ->
                    PIPELINE_TRIGGER_TYPE_CRON.equals(trigger.getType())).findFirst();

            if(triggerOptional.isPresent()) {
                cronTrigger = triggerOptional.get();
            } else {
                cronTrigger = null;
            }
        }

        triggers.forEach((desc, trigger) -> {
            PipelineTrigger pipelineTrigger = null;
            if (trigger instanceof SCMTrigger) {
                pipelineTrigger = new PipelineTriggerBuilder().withType(Constants.PIPELINE_TRIGGER_TYPE_CODE_CHANGE)
                        .withNewCodeChange().withEnabled(true).withPeriodicCheck(trigger.getSpec()).endCodeChange().build();
            } else if (trigger instanceof TimerTrigger) {
                if(cronTrigger == null) {
                    pipelineTrigger = new PipelineTriggerBuilder().withType(Constants.PIPELINE_TRIGGER_TYPE_CRON)
                            .withNewCron().withEnabled(true).withRule(trigger.getSpec()).endCron().build();
                } else {
                    PipelineTriggerCron cron = new PipelineTriggerCron();
                    cron.setEnabled(true);
                    cron.setRule(trigger.getSpec());
                    cronTrigger.setCron(cron);
                    pipelineTrigger = cronTrigger;
                }
            }

            if (pipelineTrigger != null) {
                pipelineConfig.getSpec().getTriggers().add(pipelineTrigger);
            } else {
                LOGGER.warning(() -> "Not support trigger type : " + trigger.getClass());
            }
        });
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
        } else {
            LOGGER.warning("Not spec in PipelineConfig");
            return false;
        }

        // checking if there are triggers to be updated
        updateTrigger(job, pipelineConfig);

        // take care of job's params
        updateParameters(job, pipelineConfig);

        FlowDefinition definition = job.getDefinition();
        if (definition instanceof CpsScmFlowDefinition) {
            CpsScmFlowDefinition cpsScmFlowDefinition = (CpsScmFlowDefinition) definition;
            String scriptPath = cpsScmFlowDefinition.getScriptPath();
            if (scriptPath != null && scriptPath.trim().length() > 0) {
                boolean rc = false;
                PipelineSource source = getOrCreatePipelineSource(spec);

                if (!scriptPath.equals(pipelineStrategyJenkins.getJenkinsfilePath())) {
                    LOGGER.log(Level.FINE, "updating PipelineConfig " + namespaceName + " jenkinsfile path to " + scriptPath + " from ");
                    rc = true;
                    pipelineStrategyJenkins.setJenkinsfilePath(scriptPath);
                }

                SCM scm = cpsScmFlowDefinition.getScm();
                if (scm instanceof GitSCM) {
                    populateFromGitSCM(pipelineConfig, source, (GitSCM) scm, null);
                    LOGGER.log(Level.FINE, "updating bc " + namespaceName);
                    rc = true;
                } else {
                    LOGGER.warning(() -> "Not support scm type: " + scm);
                }

                return rc;
            }

            return false;
        }

        if (definition instanceof CpsFlowDefinition) {
            CpsFlowDefinition cpsFlowDefinition = (CpsFlowDefinition) definition;
            String jenkinsfile = cpsFlowDefinition.getScript();
            if (jenkinsfile != null && jenkinsfile.trim().length() > 0 && !jenkinsfile.equals(pipelineStrategyJenkins.getJenkinsfile())) {
                LOGGER.log(Level.FINE, "updating PipelineConfig " + namespaceName + " jenkinsfile to " + jenkinsfile + " where old jenkinsfile was " + pipelineStrategyJenkins.getJenkinsfile());
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

        LOGGER.warning("Cannot update PipelineConfig " + namespaceName + " as the definition is of class " + (definition == null ? "null" : definition.getClass().getName()));
        return false;
    }

    private static void updateParameters(WorkflowJob job, PipelineConfig pipelineConfig) {
        PipelineConfigSpec spec = pipelineConfig.getSpec();

        if (spec.getParameters() == null) {
            spec.setParameters(new ArrayList<>());
        } else {
            spec.getParameters().clear();
        }

        ParametersDefinitionProperty paramsDefPro = job.getProperty(ParametersDefinitionProperty.class);
        if (paramsDefPro == null) {
            return;
        }

        List<ParameterDefinition> paramDefs = paramsDefPro.getParameterDefinitions();
        if (paramDefs == null || paramDefs.size() == 0) {
            return;
        }

        for (ParameterDefinition def : paramDefs) {
            spec.getParameters().add(convertTo(def));
        }
    }

    public static boolean isSupportParamType(@Nonnull ParameterDefinition paramDef) {
        return (StringParameterDefinition.class.equals(paramDef.getClass()))
                || (BooleanParameterDefinition.class.equals(paramDef.getClass()));
    }

    @Nonnull
    private static PipelineParameter convertTo(ParameterDefinition def) {
        if (!isSupportParamType(def)) {
            String errDesc = "Not support type:" + def.getType() + ", please fix these.";

            def = new StringParameterDefinition(def.getName(), "", errDesc);
        }

        String value = "";
        ParameterValue defVal = def.getDefaultParameterValue();
        if (defVal != null && defVal.getValue() != null) {
            value = defVal.getValue().toString();
        }

        return new PipelineParameterBuilder().withType(paramType(def)).withName(def.getName()).withValue(value).withDescription(def.getDescription()).build();
    }

    /**
     * TODO need to optimize
     * @param parameterDefinition ParameterDefinition
     * @return param type
     */
    public static String paramType(@Nonnull ParameterDefinition parameterDefinition) {
        Map<String, String> map = new HashMap<String, String>();
        map.put(new StringParameterDefinition("", "").getType(), Constants.PIPELINE_PARAMETER_TYPE_STRING);
        map.put(new BooleanParameterDefinition("", false, "").getType(), Constants.PIPELINE_PARAMETER_TYPE_BOOLEAN);

        return map.get(parameterDefinition.getType());
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
