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
package io.alauda.jenkins.devops.sync.constants;

/**
 * Alauda k8s resources annotations
 */
public final class Annotations {
    private Annotations(){}
    public static final String JENKINS_JOB_PATH = "jenkins.alauda.io/job-path";
    public static final String GENERATED_BY = "jenkins.alauda.io/generated-by";
    public static final String GENERATED_BY_JENKINS = "jenkins";
    public static final String DISABLE_SYNC_CREATE = "jenkins.alauda.io/disable-sync-create";

    public static final String MULTI_BRANCH_BRANCH = "alauda.io/jenkins.branch";
    public static final String MULTI_BRANCH_STALE_BRANCH = "alauda.io/jenkins.stale.branch";
    public static final String MULTI_BRANCH_PR = "alauda.io/jenkins.pr";
    public static final String MULTI_BRANCH_STALE_PR = "alauda.io/jenkins.stale.pr";
}
