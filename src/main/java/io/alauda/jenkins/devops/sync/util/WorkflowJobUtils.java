package io.alauda.jenkins.devops.sync.util;

import hudson.model.Job;
import io.alauda.jenkins.devops.sync.PipelineConfigProjectProperty;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;

public final class WorkflowJobUtils {
    private WorkflowJobUtils(){}

    public static boolean hasAlaudaProperty(Job job) {
        WorkflowJobProperty property = getAlaudaProperty(job);

        return (property != null);
    }

    public static boolean hasNotAlaudaProperty(Job job) {
        return !hasAlaudaProperty(job);
    }

    public static WorkflowJobProperty getAlaudaProperty(Job job) {
        WorkflowJobProperty property = (WorkflowJobProperty) job.getProperty(WorkflowJobProperty.class);
        if(property == null) {
            property = (WorkflowJobProperty) job.getProperty(PipelineConfigProjectProperty.class);
        }
        return property;
    }
}
