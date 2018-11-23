package io.alauda.jenkins.devops.sync;

import hudson.model.ItemGroup;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.kubernetes.api.model.PipelineConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

public class ConvertToWorkflow implements PipelineConfigConvert<WorkflowJob> {
    @Override
    public WorkflowJob convert(PipelineConfig pipelineConfig) {
        String jobName = AlaudaUtils.jenkinsJobName(pipelineConfig);
        String jobFullName = AlaudaUtils.jenkinsJobFullName(pipelineConfig);
        WorkflowJob job = PipelineConfigToJobMap.getJobFromPipelineConfig(pipelineConfig);
        Jenkins activeInstance = Jenkins.getInstance();
        ItemGroup parent = activeInstance;
        if (job == null) {
            job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
        }
        boolean newJob = job == null;
        if (newJob) {
            parent = AlaudaUtils.getFullNameParent(activeInstance, jobFullName, AlaudaUtils.getNamespace(pipelineConfig));
            job = new WorkflowJob(parent, jobName);
        }

        return null;
    }
}
