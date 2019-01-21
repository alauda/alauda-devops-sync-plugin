package io.alauda.jenkins.devops.sync.util;

import hudson.model.Job;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;

public class WorkflowJobUtils {
    public static boolean hasAlaudaProperty(Job job) {
        return (job != null && job.getProperty(WorkflowJobProperty.class) != null);
    }

    public static boolean hasNotAlaudaProperty(Job job) {
        return !hasAlaudaProperty(job);
    }
}
