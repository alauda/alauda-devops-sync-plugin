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

public interface Constants {
	String ALAUDA_DEVOPS_DEFAULT_NAMESPACE = "default";

	String ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER = "alauda.io/pipeline.number";
  String ALAUDA_DEVOPS_ANNOTATIONS_COMMIT = "alauda.io/commit";
	String ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG = "pipelineConfig";
	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI = "alauda.io/jenkins-build-uri";
	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL = "alauda.io/jenkins-log-url";
	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL = "alauda.io/jenkins-console-log-url";
	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL = "alauda.io/jenkins-blueocean-log-url";
	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON = "alauda.io/jenkins-pending-input-actions-json";

	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STATUS_JSON = "alauda.io/jenkins-status-json";
  String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_JSON = "alauda.io/jenkins-stages-json";
	String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_NAMESPACE = "alauda.io/jenkins-namespace";

	String ALAUDA_DEVOPS_SECRETS_DATA_USERNAME = "username";
	String ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD = "password";
	String ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY = "ssh-privatekey";
	String ALAUDA_DEVOPS_SECRETS_TYPE_SSH = "kubernetes.io/ssh-auth";
	String ALAUDA_DEVOPS_SECRETS_TYPE_BASICAUTH = "kubernetes.io/basic-auth";
	String ALAUDA_DEVOPS_SECRETS_TYPE_DOCKER = "kubernetes.io/dockerconfigjson";
  String ALAUDA_DEVOPS_SECRETS_DATA_DOCKER = ".dockerconfigjson";
	String ALAUDA_DEVOPS_SECRETS_TYPE_OPAQUE = "Opaque";
	String ALAUDA_DEVOPS_PIPELINE_STATUS_FIELD = "status";
	
	String ALAUDA_PROJECT_ENV_VAR_NAME = "PROJECT_NAME";
  String KUBERNETES_SERVICE_ACCOUNT_NAMESPACE = "/run/secrets/kubernetes.io/serviceaccount/namespace";


  String PIPELINE_PARAMETER_TYPE_STRING = "string";
  String PIPELINE_PARAMETER_TYPE_BOOLEAN = "boolean";


  String PIPELINE_TRIGGER_TYPE_MANUAL = "manual";
  String PIPELINE_TRIGGER_TYPE_CRON = "cron";
  String PIPELINE_TRIGGER_TYPE_CODE_CHANGE = "codeChange";

  String PIPELINE_RUN_POLICY_SERIAL = "Serial";
  String PIPELINE_RUN_POLICY_PARALLEL = "Parallel";
  String PIPELINE_RUN_POLICY_DEFAULT = PIPELINE_RUN_POLICY_SERIAL;

  String FOLDER_DESCRIPTION = "Folder for the Alauda DevOps project: ";

  String JOB_STATUS_QUEUE = "QUEUED";
  String JOB_STATUS_RUNNING = "RUNNING";
  String JOB_STATUS_FINISHED = "FINISHED";
  String JOB_STATUS_PAUSED = "PAUSED";
  String JOB_STATUS_SKIPPED = "SKIPPED";
  String JOB_STATUS_NOT_BUILT = "NOT_BUILT";
  String JOB_STATUS_UNKNOWN = "UNKNOWN";
}
