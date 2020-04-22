/*
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

import static io.alauda.jenkins.devops.sync.constants.Constants.DEFAULT_JENKINS_FILEPATH;
import static org.apache.commons.lang.StringUtils.isNotBlank;

import hudson.model.BooleanParameterDefinition;
import hudson.model.ParameterDefinition;
import hudson.model.ParameterValue;
import hudson.model.ParametersDefinitionProperty;
import hudson.model.StringParameterDefinition;
import hudson.plugins.git.BranchSpec;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.SubmoduleConfig;
import hudson.plugins.git.UserRemoteConfig;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.scm.SCM;
import hudson.scm.SubversionSCM;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigSpec;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameterBuilder;
import io.alauda.devops.java.client.models.V1alpha1PipelineSource;
import io.alauda.devops.java.client.models.V1alpha1PipelineSourceGit;
import io.alauda.devops.java.client.models.V1alpha1PipelineSourceSvn;
import io.alauda.devops.java.client.models.V1alpha1PipelineStrategy;
import io.alauda.devops.java.client.models.V1alpha1PipelineStrategyJenkins;
import io.alauda.devops.java.client.models.V1alpha1PipelineTrigger;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.branch.Branch;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;

public abstract class PipelineConfigToJobMapper {

  private static final Logger LOGGER = Logger.getLogger(PipelineConfigToJobMapper.class.getName());

  private PipelineConfigToJobMapper() {}

  /**
   * Create FlowDefinition according PipelineConfig. Any error will be put into conditions.
   *
   * @param pc PipelineConfig
   * @return pipeline object
   * @throws IOException in case of io exception
   */
  public static FlowDefinition mapPipelineConfigToFlow(V1alpha1PipelineConfig pc)
      throws IOException, PipelineConfigConvertException {
    // TODO move to converter
    if (!AlaudaUtils.isPipelineStrategyPipelineConfig(pc)) {
      return null;
    }

    V1alpha1PipelineConfigSpec spec = pc.getSpec();
    V1alpha1PipelineSource source = null;
    String jenkinsfile = null;
    String jenkinsfilePath = null;
    if (spec != null) {
      source = spec.getSource();
      V1alpha1PipelineStrategy strategy = spec.getStrategy();
      if (strategy != null) {
        V1alpha1PipelineStrategyJenkins pipelineStrategyJenkins = strategy.getJenkins();
        if (pipelineStrategyJenkins != null) {
          jenkinsfile = pipelineStrategyJenkins.getJenkinsfile();
          jenkinsfilePath = pipelineStrategyJenkins.getJenkinsfilePath();
        }
      }
    }

    if (StringUtils.isBlank(jenkinsfile)) {
      // Is this a Jenkinsfile from Git SCM?
      if (AlaudaUtils.isValidSource(source)) { // check null just for sonar rules
        if (jenkinsfilePath == null) {
          jenkinsfilePath = DEFAULT_JENKINS_FILEPATH;
        }

        SCM scm = createSCM(pc);
        return new CpsScmFlowDefinition(scm, jenkinsfilePath);
      } else {

        throw new PipelineConfigConvertException(
            "PipelineConfig does not contain source repository information - "
                + "cannot map PipelineConfig to Jenkins job");
      }
    } else {
      return new CpsFlowDefinition(jenkinsfile, true);
    }
  }

  /**
   * Find PipelineTrigger according to the type
   *
   * @param pipelineConfig PipelineConfig
   * @param type type of PipelineTrigger
   * @return target PipelineTrigger. Return null if can not find it.
   */
  private static V1alpha1PipelineTrigger findPipelineTriggers(
      @Nonnull V1alpha1PipelineConfig pipelineConfig, @Nonnull String type) {
    List<V1alpha1PipelineTrigger> triggers = pipelineConfig.getSpec().getTriggers();
    if (triggers == null) {
      return null;
    }

    for (V1alpha1PipelineTrigger trigger : triggers) {
      if (trigger.getType().equals(type)) {
        return trigger;
      }
    }

    return null;
  }

  /**
   * Updates the {@link V1alpha1PipelineConfig} if the Jenkins {@link WorkflowJob} changes
   *
   * @param job the job thats been updated via Jenkins
   * @param pipelineConfig the Alauda DevOps PipelineConfig to update
   * @return true if the PipelineConfig was changed
   */
  public static boolean updatePipelineConfigFromJob(
      WorkflowJob job, V1alpha1PipelineConfig pipelineConfig) {
    NamespaceName namespaceName =
        new NamespaceName(
            pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName());
    V1alpha1PipelineStrategyJenkins pipelineStrategyJenkins = null;
    V1alpha1PipelineConfigSpec spec = pipelineConfig.getSpec();
    V1alpha1PipelineStrategy strategy = spec.getStrategy();
    if (strategy != null) {
      pipelineStrategyJenkins = strategy.getJenkins();
    } else {
      LOGGER.warning(
          () -> "No available JenkinsPipelineStrategy in the PipelineConfig " + namespaceName);
      return false;
    }

    // take care of job's params
    updateParameters(job, pipelineConfig);

    pipelineConfig.getSpec().setDisabled(job.isDisabled());

    FlowDefinition definition = job.getDefinition();
    if (definition instanceof CpsScmFlowDefinition) {
      CpsScmFlowDefinition cpsScmFlowDefinition = (CpsScmFlowDefinition) definition;
      String scriptPath = cpsScmFlowDefinition.getScriptPath();
      if (scriptPath != null && scriptPath.trim().length() > 0) {
        boolean rc = false;
        V1alpha1PipelineSource source = AlaudaUtils.getOrCreatePipelineSource(pipelineConfig);

        if (!scriptPath.equals(pipelineStrategyJenkins.getJenkinsfilePath())) {
          LOGGER.log(
              Level.FINE,
              "updating PipelineConfig "
                  + namespaceName
                  + " jenkinsfile path to "
                  + scriptPath
                  + " from ");
          rc = true;
          pipelineStrategyJenkins.setJenkinsfilePath(scriptPath);
        }

        SCM scm = cpsScmFlowDefinition.getScm();
        if (scm instanceof GitSCM) {
          populateFromGitSCM(pipelineConfig, source, (GitSCM) scm, null);
          LOGGER.log(Level.FINE, "updating bc " + namespaceName);
          rc = true;
        } else if (scm instanceof SubversionSCM) {
          populateFromSvnSCM(pipelineConfig, source, (SubversionSCM) scm);
          LOGGER.log(Level.FINE, "updating bc " + namespaceName);
          rc = true;
        } else {
          LOGGER.warning(() -> "Not support scm type: " + scm);
        }

        return rc;
      }

      return false;
    } else if (definition instanceof CpsFlowDefinition) {
      CpsFlowDefinition cpsFlowDefinition = (CpsFlowDefinition) definition;
      String jenkinsfile = cpsFlowDefinition.getScript();
      if (jenkinsfile != null
          && jenkinsfile.trim().length() > 0
          && !jenkinsfile.equals(pipelineStrategyJenkins.getJenkinsfile())) {
        LOGGER.log(
            Level.FINE,
            "updating PipelineConfig "
                + namespaceName
                + " jenkinsfile to "
                + jenkinsfile
                + " where old jenkinsfile was "
                + pipelineStrategyJenkins.getJenkinsfile());
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
        V1alpha1PipelineSource source = AlaudaUtils.getOrCreatePipelineSource(pipelineConfig);
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

    LOGGER.warning(
        "Cannot update PipelineConfig "
            + namespaceName
            + " as the definition is of class "
            + (definition == null ? "null" : definition.getClass().getName()));
    return false;
  }

  public static void updateParameters(WorkflowJob job, V1alpha1PipelineConfig pipelineConfig) {
    V1alpha1PipelineConfigSpec spec = pipelineConfig.getSpec();

    if (spec.getParameters() != null) {
      spec.getParameters().clear();
    }

    for (V1alpha1PipelineParameter param : getPipelineParameter(job)) {
      spec.addParametersItem(param);
    }
  }

  public static List<V1alpha1PipelineParameter> getPipelineParameter(@Nonnull WorkflowJob job) {
    List<V1alpha1PipelineParameter> pipelineParameters = new ArrayList<>();

    ParametersDefinitionProperty paramsDefPro = job.getProperty(ParametersDefinitionProperty.class);
    if (paramsDefPro == null) {
      LOGGER.log(Level.FINE, "No parameters define property for job {0}", job);
      return pipelineParameters;
    }

    List<ParameterDefinition> paramDefs = paramsDefPro.getParameterDefinitions();
    if (paramDefs == null || paramDefs.size() == 0) {
      LOGGER.log(Level.FINE, "No parameters defined for job {0}", job);
      return pipelineParameters;
    } else {
      LOGGER.log(Level.FINE, "ParameterDefinitions job {0} are {1}", new Object[] {job, paramDefs});
    }

    for (ParameterDefinition def : paramDefs) {
      pipelineParameters.add(convertTo(def));
    }
    return pipelineParameters;
  }

  public static boolean isSupportParamType(@Nonnull ParameterDefinition paramDef) {
    return (StringParameterDefinition.class.equals(paramDef.getClass()))
        || (BooleanParameterDefinition.class.equals(paramDef.getClass()));
  }

  @Nonnull
  private static V1alpha1PipelineParameter convertTo(ParameterDefinition def) {
    if (!isSupportParamType(def)) {
      String errDesc = "Not support type:" + def.getType() + ", please fix these.";

      def = new StringParameterDefinition(def.getName(), "", errDesc);
    }

    String value = "";
    ParameterValue defVal = def.getDefaultParameterValue();
    if (defVal != null && defVal.getValue() != null) {
      value = defVal.getValue().toString();
    }

    return new V1alpha1PipelineParameterBuilder()
        .withType(paramType(def))
        .withName(def.getName())
        .withValue(value)
        .withDescription(def.getDescription())
        .build();
  }

  /**
   * @param parameterDefinition ParameterDefinition
   * @return param type
   */
  private static String paramType(@Nonnull ParameterDefinition parameterDefinition) {
    return parameterDefinition.getType();
  }

  private static boolean populateFromGitSCM(
      V1alpha1PipelineConfig pipelineConfig,
      V1alpha1PipelineSource source,
      GitSCM gitSCM,
      String ref) {
    if (source.getGit() == null) {
      source.setGit(new V1alpha1PipelineSourceGit());
    }

    source.setSourceType(Constants.SOURCE_TYPE_GIT);

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

  private static boolean populateFromSvnSCM(
      V1alpha1PipelineConfig pipelineConfig,
      V1alpha1PipelineSource source,
      SubversionSCM subversionSCM) {
    if (source.getSvn() == null) {
      source.setSvn(new V1alpha1PipelineSourceSvn());
    }
    source.setSourceType(Constants.SOURCE_TYPE_SVN);

    SubversionSCM.ModuleLocation[] locations = subversionSCM.getLocations();
    if (locations != null && locations.length > 0) {
      SubversionSCM.ModuleLocation location = locations[0];
      String url = location.getURL();

      AlaudaUtils.updateSvnSourceUrl(pipelineConfig, url);
      return true;
    }

    return false;
  }

  private static SCM createSCM(V1alpha1PipelineConfig pc) throws IOException {
    V1alpha1PipelineSource source = pc.getSpec().getSource();
    if (AlaudaUtils.isValidGitSource(source)
        && (source.getSourceType().equals(Constants.SOURCE_TYPE_GIT)
            || source.getSourceType().equals(""))) {
      return createGitSCM(pc, source);
    } else if (AlaudaUtils.isValidSvnSource(source)
        && source.getSourceType().equals(Constants.SOURCE_TYPE_SVN)) {
      return createSvnSCM(pc, source);
    } else {
      return null;
    }
  }

  private static GitSCM createGitSCM(V1alpha1PipelineConfig pc, V1alpha1PipelineSource source)
      throws IOException {
    V1alpha1PipelineSourceGit gitSource = source.getGit();
    String branchRef = gitSource.getRef();
    List<BranchSpec> branchSpecs = Collections.emptyList();
    if (isNotBlank(branchRef)) {
      branchSpecs = Collections.singletonList(new BranchSpec(branchRef));
    }

    String credentialId = CredentialsUtils.getSCMSourceCredentialsId(pc);

    // if credentialsID is null, go with an SCM where anonymous has to be sufficient
    List<UserRemoteConfig> configs =
        Collections.singletonList(
            new UserRemoteConfig(gitSource.getUri(), null, null, credentialId));

    return new GitSCM(
        configs,
        branchSpecs,
        false,
        Collections.<SubmoduleConfig>emptyList(),
        null,
        null,
        Collections.<GitSCMExtension>emptyList());
  }

  private static SubversionSCM createSvnSCM(
      V1alpha1PipelineConfig pc, V1alpha1PipelineSource source) throws IOException {
    V1alpha1PipelineSourceSvn svnSource = source.getSvn();
    String credentialId = CredentialsUtils.getSCMSourceCredentialsId(pc);

    return new SubversionSCM(svnSource.getUri(), credentialId, ".");
  }
}
