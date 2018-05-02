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

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobProperty;
import hudson.model.JobPropertyDescriptor;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import static io.alauda.jenkins.devops.sync.AlaudaUtils.getAuthenticatedAlaudaClient;

/**
 * Stores the Alauda DevOps Pipeline Config related project properties.
 *
 * - Namespace - Pipeline Config name - Pipeline Config uid - Pipeline Config resource
 * version - Pipeline Config run policy
 */
public class PipelineConfigProjectProperty extends JobProperty<Job<?, ?>> {

  // The build config uid this job relates to.
  private String uid;

  private String namespace;

  private String name;

  private String resourceVersion;

  private String pipelineRunPolicy;

  @DataBoundConstructor
  public PipelineConfigProjectProperty(String namespace, String name,
                                    String uid, String resourceVersion, String pipelineRunPolicy) {
    this.namespace = namespace;
    this.name = name;
    this.uid = uid;
    this.resourceVersion = resourceVersion;
    this.pipelineRunPolicy = pipelineRunPolicy;
  }

  public PipelineConfigProjectProperty(PipelineConfig pc) {
    this(pc.getMetadata().getNamespace(), pc.getMetadata().getName(), pc
        .getMetadata().getUid(), pc.getMetadata().getResourceVersion(),
      pc.getSpec().getRunPolicy());
  }

  public PipelineConfig getPipelineConfig() {
    PipelineConfig pc = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs()
      .inNamespace(namespace).withName(name).get();
    if (pc != null && pc.getMetadata().getUid().equals(uid)) {
      return pc;
    }
    return null;
  }

  public String getUid() {
    return uid;
  }

  public void setUid(String uid) {
    this.uid = uid;
  }

  public String getName() {
    return name;
  }

  public void setName(String name) {
    this.name = name;
  }

  public String getNamespace() {
    return namespace;
  }

  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  public String getResourceVersion() {
    return resourceVersion;
  }

  public void setResourceVersion(String resourceVersion) {
    this.resourceVersion = resourceVersion;
  }

  public String getPipelineRunPolicy() {
    return pipelineRunPolicy;
  }

  public void setPipelineRunPolicy(String pipelineRunPolicy) {
    this.pipelineRunPolicy = pipelineRunPolicy;
  }

  @Extension
  public static final class DescriptorImpl extends JobPropertyDescriptor {
    public boolean isApplicable(Class<? extends Job> jobType) {
      return WorkflowJob.class.isAssignableFrom(jobType);
    }
  }
}
