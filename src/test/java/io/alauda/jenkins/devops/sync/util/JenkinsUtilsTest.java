package io.alauda.jenkins.devops.sync.util;

import hudson.model.FreeStyleProject;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineList;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.WithoutJenkins;

import java.io.IOException;
import java.util.ArrayList;

import static org.junit.Assert.*;

public class JenkinsUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void getJob() throws IOException {
        FreeStyleProject freeStyleProject = j.createFreeStyleProject();

        assertNotNull("can't found job", JenkinsUtils.getJob(freeStyleProject.getName()));
        assertNull(JenkinsUtils.getJob("hello"));
    }

    @Test
    public void getRootUrl() {
        assertNotNull(JenkinsUtils.getRootUrl());
    }

    @Test
    public void getBuildConfigName() throws InterruptedException {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        assertNotNull(config);


        WorkflowJob job = JobUtils.findWorkflowJob(j.jenkins,
                config.getMetadata().getNamespace(), config.getMetadata().getName());
        assertNotNull(JenkinsUtils.getBuildConfigName(job));
        assertNotNull(JenkinsUtils.getFullJobName(job));
    }

    @Test
    @WithoutJenkins
    public void filterNew() {
        assertNull(JenkinsUtils.filterNew(null));

        PipelineList list = new PipelineList();
        assertNotNull(JenkinsUtils.filterNew(list));

        list.setItems(new ArrayList<>());
        assertNotNull(JenkinsUtils.filterNew(list));
        assertEquals(0, JenkinsUtils.filterNew(list).getItems().size());
    }
}
