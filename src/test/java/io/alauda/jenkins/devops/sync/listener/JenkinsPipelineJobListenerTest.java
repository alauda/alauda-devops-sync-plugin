package io.alauda.jenkins.devops.sync.listener;

import hudson.model.listeners.ItemListener;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;

public class JenkinsPipelineJobListenerTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void simple() throws IOException, InterruptedException {
        JenkinsPipelineJobListener listener = new JenkinsPipelineJobListener();
        assertNotNull(listener);

        // not enable
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        config.setEnabled(false);
        config.configChange();

        MockFolder folder = j.createFolder("test");

        WorkflowJob wfJob = folder.createProject(WorkflowJob.class, "test");
        ItemListener.fireOnUpdated(wfJob);
        wfJob.delete();
    }
}
