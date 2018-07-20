package io.alauda.jenkins.devops.sync.util;

import hudson.model.Job;
import hudson.model.TopLevelItem;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class JobUtils {
    public static Job findJob(Jenkins jenkins, String folderName, String jobName)
            throws InterruptedException {
        Job jobItem = null;
        int i = 0;
        while(i++ <= 10) {
            TopLevelItem folder = jenkins.getItem(folderName);
            if(folder != null) {
                jobItem = folder.getAllJobs().stream()
                        .filter(job -> ((Job) job).getName().equals(folderName + "-" + jobName))
                        .findFirst()
                        .orElse(null);
                if(jobItem != null) {
                    break;
                }
            }

            Thread.sleep(1000);
        }

        return jobItem;
    }

    public static WorkflowJob findWorkflowJob(Jenkins jenkins, String folderName, String jobName) throws InterruptedException {
        Job jobItem = JobUtils.findJob(jenkins, folderName, jobName);
        assertNotNull(jobItem);
        assertEquals(jobItem.getClass(), WorkflowJob.class);
        return (WorkflowJob) jobItem;
    }
}