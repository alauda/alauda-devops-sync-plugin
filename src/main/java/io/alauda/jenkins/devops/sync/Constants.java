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


/**
 */
public class Constants {
	public static final String ALAUDA_DEVOPS_DEFAULT_NAMESPACE = "default";

	public static final String ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER = "alauda.io/pipeline.number";
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_COMMIT = "alauda.io/commit";
	public static final String ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG = "pipelineConfig";
	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BUILD_URI = "alauda.io/jenkins-build-uri";
	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_LOG_URL = "alauda.io/jenkins-log-url";
	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_CONSOLE_LOG_URL = "alauda.io/jenkins-console-log-url";
	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_BLUEOCEAN_LOG_URL = "alauda.io/jenkins-blueocean-log-url";
	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_PENDING_INPUT_ACTION_JSON = "alauda.io/jenkins-pending-input-actions-json";

	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STATUS_JSON = "alauda.io/jenkins-status-json";
  public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_STAGES_JSON = "alauda.io/jenkins-stages-json";
	public static final String ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_NAMESPACE = "alauda.io/jenkins-namespace";
  // see PR https://github.com/openshift/jenkins-sync-plugin/pull/189, there was a issue with having "/"
	// in a label we construct a watch over, where usual UTF-8 encoding of the label name (which becomes part of 
	// a query param on the REST invocation) was causing okhttp3 to complain (there is even more history/discussion
	// in the PR as to issues with fixing).
	// so we avoid use of "/" for this label
    public static final String OPENSHIFT_LABELS_SECRET_CREDENTIAL_SYNC = "credential.sync.jenkins.openshift.io";
    public static final String VALUE_SECRET_SYNC = "true";

	public static final String ALAUDA_DEVOPS_SECRETS_DATA_USERNAME = "username";
	public static final String ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD = "password";
	public static final String ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY = "ssh-privatekey";
	public static final String ALAUDA_DEVOPS_SECRETS_TYPE_SSH = "kubernetes.io/ssh-auth";
	public static final String ALAUDA_DEVOPS_SECRETS_TYPE_BASICAUTH = "kubernetes.io/basic-auth";
	public static final String ALAUDA_DEVOPS_SECRETS_TYPE_DOCKER = "kubernetes.io/dockerconfigjson";
  public static final String ALAUDA_DEVOPS_SECRETS_DATA_DOCKER = ".dockerconfigjson";
	public static final String ALAUDA_DEVOPS_SECRETS_TYPE_OPAQUE = "Opaque";
	public static final String ALAUDA_DEVOPS_PIPELINE_STATUS_FIELD = "status";
	
	public static final String OPENSHIFT_PROJECT_ENV_VAR_NAME = "PROJECT_NAME";
    public static final String KUBERNETES_SERVICE_ACCOUNT_NAMESPACE = "/run/secrets/kubernetes.io/serviceaccount/namespace";


    public static final String PIPELINE_PARAMETER_TYPE_STRING = "string";
  public static final String PIPELINE_PARAMETER_TYPE_BOOLEAN = "boolean";


  public static final String PIPELINE_TRIGGER_TYPE_MANUAL = "manual";
  public static final String PIPELINE_TRIGGER_TYPE_CRON = "cron";
  public static final String PIPELINE_TRIGGER_TYPE_CODE_CHANGE = "codeChange";

  public static final String PIPELINE_RUN_POLICY_SERIAL = "Serial";
  public static final String PIPELINE_RUN_POLICY_PARALLEL = "Parallel";
  public static final String PIPELINE_RUN_POLICY_DEFAULT = PIPELINE_RUN_POLICY_SERIAL;

  public static final String FOLDER_DESCRIPTION = "Folder for the Alauda DevOps project: ";

  public static final String JOB_STATUS_QUEUE = "QUEUED";
  public static final String JOB_STATUS_RUNNING = "RUNNING";
  public static final String JOB_STATUS_FINISHED = "FINISHED";
  public static final String JOB_STATUS_PAUSED = "PAUSED";
  public static final String JOB_STATUS_SKIPPED = "SKIPPED";
  public static final String JOB_STATUS_NOT_BUILT = "NOT_BUILT";
  public static final String JOB_STATUS_UNKNOWN = "UNKNOWN";


}
