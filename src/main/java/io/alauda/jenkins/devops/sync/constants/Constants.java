/**
 * Copyright (C) 2018 Alauda.io
 *
 * <p>Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 * <p>http://www.apache.org/licenses/LICENSE-2.0
 *
 * <p>Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.constants;

import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;

public final class Constants {
  private Constants() {}

  public static final String ALAUDA_SYNC_PLUGIN = "sync.plugin.alauda.io";
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("pipeline.number");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_COMMIT =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("commit");
  public static final String ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG = "pipelineConfig";
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-build-uri");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-log-url");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL =
      ResourceControllerManager.getControllerManager()
          .getFormatedAnnotation("jenkins-console-log-url");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL =
      ResourceControllerManager.getControllerManager()
          .getFormatedAnnotation("jenkins-blueocean-log-url");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_LOG =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-stages-log");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-stages");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STEPS_LOG =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-steps-log");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STEPS =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-steps");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_VIEW_LOG =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-view-log");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PROGRESSIVE_LOG =
      ResourceControllerManager.getControllerManager()
          .getFormatedAnnotation("jenkins-progressive-log");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_CAUSES_DETAILS =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("causes-details");

  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON =
      ResourceControllerManager.getControllerManager()
          .getFormatedAnnotation("jenkins-pending-input-actions-json");

  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STATUS_JSON =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-status-json");
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_JSON =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-stages-json");

  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_NAMESPACE =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-namespace");

  public static final String ALAUDA_DEVOPS_ANNOTATIONS_MULTI_BRANCH_SCAN_LOG =
      ResourceControllerManager.getControllerManager()
          .getFormatedAnnotation("multi-branch-scan-log");

  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_IDENTITY =
      ResourceControllerManager.getControllerManager()
          .getFormatedAnnotation("jenkins-instance-identity");

  public static final String ALAUDA_DEVOPS_ANNOTATIONS_BASEDOMAIN = "platform-basedomain";
  public static final String ALAUDA_DEVOPS_USED_BASEDOMAIN = "alauda.io";

  /** secret keys */
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_USERNAME = "username";

  public static final String ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD = "password"; // NOSONAR
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY = "ssh-privatekey";
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_DOCKER = ".dockerconfigjson";
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_ACCESSTOKEN = "accessToken";
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_ACCESSTOKENKEY = "accessTokenKey";
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_PASSPHRASE = "passphrase"; // NOSONAR

  /** secret types */
  public static final String ALAUDA_DEVOPS_SECRETS_TYPE_SSH = "kubernetes.io/ssh-auth";

  public static final String ALAUDA_DEVOPS_SECRETS_TYPE_DOCKER = "kubernetes.io/dockerconfigjson";
  public static final String ALAUDA_DEVOPS_SECRETS_TYPE_OAUTH2 = "devops.alauda.io/oauth2";

  public static final String ALAUDA_PROJECT_ENV_VAR_NAME = "PROJECT_NAME";
  public static final String KUBERNETES_SERVICE_ACCOUNT_NAMESPACE =
      "/run/secrets/kubernetes.io/serviceaccount/namespace";

  @Deprecated public static final String PIPELINE_PARAMETER_TYPE_STRING = "string";
  public static final String PIPELINE_PARAMETER_TYPE_STRING_DEF = "StringParameterDefinition";
  @Deprecated public static final String PIPELINE_PARAMETER_TYPE_BOOLEAN = "boolean";
  public static final String PIPELINE_PARAMETER_TYPE_BOOLEAN_DEF = "BooleanParameterDefinition";

  public static final String PIPELINE_TRIGGER_TYPE_CRON = "cron";
  public static final String PIPELINE_TRIGGER_TYPE_CODE_CHANGE = "codeChange";
  /** Triggered by branch scanning */
  public static final String PIPELINE_TRIGGER_TYPE_BRANCH_SCAN = "branchScan";
  /** Triggered by an upstream job */
  public static final String PIPELINE_TRIGGER_TYPE_UPSTREAM_CAUSE = "upstreamCause";
  /** Triggered by multi-causes */
  public static final String PIPELINE_TRIGGER_TYPE_MULTI_CAUSES = "multiCauses";
  /** Triggered by unknown cause */
  public static final String PIPELINE_TRIGGER_TYPE_UNKNOWN_CAUSE = "unknownCause";
  /** Should be an error situation */
  public static final String PIPELINE_TRIGGER_TYPE_NOT_FOUND = "noCauseFound";

  public static final String PIPELINE_RUN_POLICY_SERIAL = "Serial";
  public static final String PIPELINE_RUN_POLICY_PARALLEL = "Parallel";

  public static final String PIPELINE_CREATED_BY = "created_by";

  public static final String FOLDER_DESCRIPTION = "Folder for the Alauda DevOps project: ";

  public static final String JOB_STATUS_QUEUE = "QUEUED";
  public static final String JOB_STATUS_RUNNING = "RUNNING";
  public static final String JOB_STATUS_FINISHED = "FINISHED";
  public static final String JOB_STATUS_SKIPPED = "SKIPPED";
  public static final String JOB_STATUS_NOT_BUILT = "NOT_BUILT";
  public static final String JOB_STATUS_UNKNOWN = "UNKNOWN";

  public static final String DEFAULT_JENKINS_FILEPATH = "Jenkinsfile";

  public static final String ANNOTATION_BADGE =
      ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins-badges");

  public static final String PIPELINECONFIG_KIND_MULTI_BRANCH = "multi-branch";
  public static final String PIPELINECONFIG_KIND = "pipeline.kind";

  public static final String GITHUB_SCM_SOURCE =
      "org.jenkinsci.plugins.github_branch_source.GitHubSCMSource";
  public static final String GITHUB_BRANCH_DISCOVERY_TRAIT =
      "org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait";
  public static final String GITHUB_ORIGIN_PR_TRAIT =
      "org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait";
  public static final String GITHUB_FORK_PR_TRAIT =
      "org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait";
  public static final String GITHUB_FORK_PR_TRUST_TRAIT =
      "org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait$TrustPermission";

  public static final String BITBUCKET_SCM_SOURCE =
      "com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource";
  public static final String BITBUCKET_BRANCH_DISCOVERY_TRAIT =
      "com.cloudbees.jenkins.plugins.bitbucket.BranchDiscoveryTrait";
  public static final String BITBUCKET_ORIGIN_PR_TRAIT =
      "com.cloudbees.jenkins.plugins.bitbucket.OriginPullRequestDiscoveryTrait";
  public static final String BITBUCKET_FORK_PR_TRAIT =
      "com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait";
  public static final String BITBUCKET_FORK_PR_TRUST_TRAIT =
      "com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait$TrustTeamForks";

  public static final String GITLAB_SCM_SOURCE =
      "io.jenkins.plugins.gitlabbranchsource.GitLabSCMSource";
  public static final String GITLAB_BRANCH_DISCOVERY_TRAIT =
      "io.jenkins.plugins.gitlabbranchsource.BranchDiscoveryTrait";
  public static final String GITLAB_ORIGIN_PR_TRAIT =
      "io.jenkins.plugins.gitlabbranchsource.OriginMergeRequestDiscoveryTrait";
  public static final String GITLAB_FORK_PR_TRAIT =
      "io.jenkins.plugins.gitlabbranchsource.ForkMergeRequestDiscoveryTrait";
  public static final String GITLAB_FORK_PR_TRUST_TRAIT =
      "io.jenkins.plugins.gitlabbranchsource.ForkMergeRequestDiscoveryTrait$TrustPermission";
  public static final String GITLAB_CONFIG_SERVERS =
      "io.jenkins.plugins.gitlabserverconfig.servers.GitLabServers";

  public static final String SOURCE_TYPE_SVN = "SVN";
  public static final String SOURCE_TYPE_GIT = "GIT";

  public static final String JENKINS_NODES_CONDITION = "nodes";
  public static final String JENKINS_PLUGINS_CONDITION = "plugins";

  public static final String JENKINS_CONDITION_STATUS_TYPE = "jenkins-status";

  public static final String JENKINS_PLUGIN_STATUS_FAILED = "failed";
  public static final String JENKINS_PLUGIN_STATUS_ACTIVE = "active";
  public static final String JENKINS_PLUGIN_STATUS_INACTIVE = "inactive";

  public static final String PIPELINE_LABELS_REPLAYED_FROM = "replayed-from";
}
