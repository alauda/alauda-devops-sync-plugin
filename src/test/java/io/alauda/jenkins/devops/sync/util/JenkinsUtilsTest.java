package io.alauda.jenkins.devops.sync.util;

import hudson.model.Action;
import hudson.model.FreeStyleProject;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineList;
import io.alauda.kubernetes.api.model.PipelineParameter;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.WithoutJenkins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_PARAMETER_TYPE_STRING;
import static io.alauda.jenkins.devops.sync.constants.Constants.ROOT_URL;
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

        JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
        assertNotNull(config);
        config.setUrl("");

        assertEquals(ROOT_URL, JenkinsUtils.getRootUrl());

        config.setUrl(" ");
        assertEquals(ROOT_URL, JenkinsUtils.getRootUrl());
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

        // one of them is null
        assertNull(JenkinsUtils.setJobRunParamsFromEnvAndUIParams(Collections.EMPTY_LIST, null));
        assertNull(JenkinsUtils.setJobRunParamsFromEnvAndUIParams(null, Collections.EMPTY_LIST));

        List<Action> buildActions = new ArrayList<>();
        // empty
        List<PipelineParameter> pipelineParameters = new ArrayList<>();
        assertNull(JenkinsUtils.setJobRunParamsFromEnvAndUIParams(pipelineParameters, buildActions));

        // not supported type
        pipelineParameters.add(new PipelineParameter());
        assertNull(JenkinsUtils.setJobRunParamsFromEnvAndUIParams(pipelineParameters, buildActions));

        // supported type
        pipelineParameters.add(new PipelineParameter("", "sdf", PIPELINE_PARAMETER_TYPE_STRING, "sdf"));
        buildActions = JenkinsUtils.setJobRunParamsFromEnvAndUIParams(pipelineParameters, buildActions);
        assertNotNull(buildActions);
        assertEquals(1, buildActions.size());
    }
}
