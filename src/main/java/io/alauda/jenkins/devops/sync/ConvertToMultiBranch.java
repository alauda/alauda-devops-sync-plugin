package io.alauda.jenkins.devops.sync;

import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

public class ConvertToMultiBranch implements PipelineConfigConvert<WorkflowMultiBranchProject> {
    @Override
    public WorkflowMultiBranchProject convert(PipelineConfig pipelineConfig) {
        return null;
    }
}
