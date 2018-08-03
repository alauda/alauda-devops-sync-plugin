package io.alauda.jenkins.devops.sync.util;

import hudson.model.FreeStyleProject;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertNotNull;

public class JenkinsUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void getJob() throws IOException {
        FreeStyleProject freeStyleProject = j.createFreeStyleProject();

        assertNotNull("can't found job", JenkinsUtils.getJob(freeStyleProject.getName()));
    }

    @Test
    public void getRootUrl() {
        assertNotNull(JenkinsUtils.getRootUrl());
    }

    @Test
    public void getBuildConfigName() {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        assertNotNull(config);


    }
}
