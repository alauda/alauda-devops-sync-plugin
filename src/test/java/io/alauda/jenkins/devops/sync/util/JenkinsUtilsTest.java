package io.alauda.jenkins.devops.sync.util;

import antlr.ANTLRException;
import hudson.model.*;
import hudson.triggers.SCMTrigger;
import io.alauda.devops.api.model.JenkinsPipelineBuildStrategy;
import io.alauda.devops.api.model.JenkinsPipelineBuildStrategyBuilder;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.jenkins.devops.sync.WithoutK8s;
import io.alauda.kubernetes.api.model.*;
import jenkins.model.JenkinsLocationConfiguration;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.WithoutJenkins;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static io.alauda.jenkins.devops.sync.util.JenkinsUtils.PARAM_FROM_ENV_DESCRIPTION;
import static org.junit.Assert.*;

public class JenkinsUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    @WithoutK8s
    public void getJob() throws IOException {
        FreeStyleProject freeStyleProject = j.createFreeStyleProject();

        assertNotNull("can't found job", JenkinsUtils.getJob(freeStyleProject.getName()));
        assertNull(JenkinsUtils.getJob("hello"));
    }

    @Test
    @WithoutK8s
    public void getRootUrl() {
        assertNotNull(JenkinsUtils.getRootUrl());

        JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
        assertNotNull(config);
        config.setUrl("");

        assertEquals(ROOT_URL, JenkinsUtils.getRootUrl());

        config.setUrl(" ");
        assertEquals(" /", JenkinsUtils.getRootUrl());
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

    @Test
    @WithoutK8s
    public void addJobParamForBuildEnvs() throws IOException {
        WorkflowJob wfJob = j.jenkins.createProject(WorkflowJob.class, "j");

        JenkinsPipelineBuildStrategy strategy = new JenkinsPipelineBuildStrategy();
        assertNull(JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, false));

        // with env
        final String envName = "name";
        final String envNameVal = "jack";
        strategy = new JenkinsPipelineBuildStrategyBuilder()
                .addNewEnv().withName(envName)
                .withValue(envNameVal).endEnv().build();

        {
            // not replace exists
            Map<String, ParameterDefinition> defMap = JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, false);
            assertNotNull(defMap);
            assertEquals(1, defMap.size());

            ParametersDefinitionProperty paramDefProperty = wfJob.getProperty(ParametersDefinitionProperty.class);
            assertNotNull(paramDefProperty);

            ParameterDefinition def = paramDefProperty.getParameterDefinition(envName);
            assertNotNull(def);
            assertEquals("wrong with ParameterDefinition", StringParameterDefinition.class,
                    def.getClass());
            assertEquals("wrong with value of parameters", envNameVal,
                    ((StringParameterDefinition) def).getDefaultValue());
            assertEquals("wrong with description", PARAM_FROM_ENV_DESCRIPTION, def.getDescription());
        }

        {
            // replace exists
            Map<String, ParameterDefinition> defMap = JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, true);
            assertNotNull(defMap);
            assertEquals(1, defMap.size());
        }

        // more env
        strategy = new JenkinsPipelineBuildStrategyBuilder()
                .addNewEnv().withName("age")
                .withValue("12").endEnv().build();

        {
            // not replace exists
            Map<String, ParameterDefinition> defMap = JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, false);
            assertNotNull(defMap);
            assertEquals(2, defMap.size());

            ParametersDefinitionProperty defPro = wfJob.getProperty(ParametersDefinitionProperty.class);
            assertNotNull(defPro);
            List<ParameterDefinition> defs = defPro.getParameterDefinitions();
            assertNotNull(defs);
            assertEquals(2, defs.size());
        }
    }

    @Test
    @WithoutK8s
    public void setJobTriggers() throws IOException, ANTLRException {
        WorkflowJob wfJob = j.jenkins.createProject(WorkflowJob.class, "p");
        wfJob.addTrigger(new SCMTrigger("* * * * *"));

        List<ANTLRException> exs = JenkinsUtils.setJobTriggers(wfJob, null);
        assertNotNull(exs);
        assertEquals(0, exs.size());

        PipelineTriggersJobProperty triggerPro = wfJob.getProperty(PipelineTriggersJobProperty.class);
        assertNotNull(triggerPro);
        assertNotNull(triggerPro.getTriggers());
        assertEquals(1, triggerPro.getTriggers().size());

        // with wrong triggers
        List<PipelineTrigger> triggers = new ArrayList<>();
        triggers.add(new PipelineTrigger());
        exs = JenkinsUtils.setJobTriggers(wfJob, triggers);
        assertNotNull(exs);
        assertEquals(0, exs.size());
        triggerPro = wfJob.getProperty(PipelineTriggersJobProperty.class);
        assertNull(triggerPro);

        // with correct triggers
        triggers.add(new PipelineTrigger(null,
                new PipelineTriggerCron(true, "* * * * *"), PIPELINE_TRIGGER_TYPE_CRON));
        exs = JenkinsUtils.setJobTriggers(wfJob, triggers);
        assertEquals(0, exs.size());
        triggerPro = wfJob.getProperty(PipelineTriggersJobProperty.class);
        assertEquals(1, triggerPro.getTriggers().size());

        // with invalid cron
        triggers.add(new PipelineTrigger(null,
                new PipelineTriggerCron(true, "abc"), PIPELINE_TRIGGER_TYPE_CRON));
        exs = JenkinsUtils.setJobTriggers(wfJob, triggers);
        assertEquals(1, exs.size());
    }
}
