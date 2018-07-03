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

import hudson.model.Cause;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.Pipeline;
import org.apache.commons.lang.StringUtils;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_COMMIT;

public class JenkinsPipelineCause extends Cause {

    private String uid;

    private String namespace;

    private String name;

    private String gitUri;

    private String commit;

    private String pipelineConfigUid;

    private int numStages = -1;

    private int numFlowNodes = -1;

    private long lastUpdateToAlaudaDevOps = -1;

    public JenkinsPipelineCause(String uid, String namespace, String name, String gitUri,
                                String commit, String pipelineConfigUid) {
        this.uid = uid;
        this.namespace = namespace;
        this.name = name;
        this.gitUri = gitUri;
        this.commit = commit;
        this.pipelineConfigUid = pipelineConfigUid;
    }

    public JenkinsPipelineCause(String uid, String namespace, String name, String gitUri,
                                String commit, String pipelineConfigUid, int numStages,
                                int numFlowNodes, long lastUpdateToAlaudaDevOps) {
        this(uid, namespace, name, gitUri, commit, pipelineConfigUid);
        this.numStages = numStages;
        this.numFlowNodes = numFlowNodes;
        this.lastUpdateToAlaudaDevOps = lastUpdateToAlaudaDevOps;
    }

    public JenkinsPipelineCause(Pipeline pipeline, String pipelineConfigUid) {
        this.pipelineConfigUid = pipelineConfigUid;
        if (pipeline == null || pipeline.getMetadata() == null) {
            return;
        }
        ObjectMeta meta = pipeline.getMetadata();
        uid = meta.getUid();
        namespace = meta.getNamespace();
        name = meta.getName();

        if (pipeline.getSpec() != null) {
            if (pipeline.getSpec().getSource() != null
                    && pipeline.getSpec().getSource().getGit() != null) {
                gitUri = pipeline.getSpec().getSource().getGit().getUri();
            }
            // TODO: add commit to pipeline spec
            // currently lets used annotations for that
            if (pipeline.getMetadata().getAnnotations() != null &&
                pipeline.getMetadata().getAnnotations().containsKey(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT)) {
              commit = pipeline.getMetadata().getAnnotations().get(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT);
            }
        }
    }

    @Override
    public String getShortDescription() {
        StringBuilder sb = new StringBuilder("Alauda DevOps Pipeline ")
                .append(namespace).append("/").append(name);

        if (StringUtils.isNotBlank(gitUri)) {
            sb.append(" from ").append(gitUri);
            if (StringUtils.isNotBlank(commit)) {
                sb.append(", commit ").append(commit);
            }
        }

        return sb.toString();
    }

    public String getUid() {
        return uid;
    }

    public String getNamespace() {
        return namespace;
    }

    public String getName() {
        return name;
    }

    public String getGitUri() {
        return gitUri;
    }

    public String getCommit() {
        return commit;
    }

    public String getPipelineConfigUid() {
        return pipelineConfigUid;
    }

    public int getNumStages() {
        return numStages;
    }

    public void setNumStages(int numStages) {
        this.numStages = numStages;
    }

    public int getNumFlowNodes() {
        return numFlowNodes;
    }

    public void setNumFlowNodes(int numFlowNodes) {
        this.numFlowNodes = numFlowNodes;
    }

    public long getLastUpdateToAlaudaDevOps() {
        return lastUpdateToAlaudaDevOps;
    }

    public void setLastUpdateToAlaudaDevOps(long lastUpdateToAlaudaDevOps) {
        this.lastUpdateToAlaudaDevOps = lastUpdateToAlaudaDevOps;
    }

}
