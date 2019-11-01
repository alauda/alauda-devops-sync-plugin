// package io.alauda.jenkins.devops.sync.util;
//
// import antlr.ANTLRException;
// import hudson.model.*;
// import hudson.triggers.SCMTrigger;
// import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
// import io.alauda.devops.java.client.models.V1alpha1PipelineParameterBuilder;
// import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
// import io.alauda.jenkins.devops.sync.WithoutK8s;
// import io.alauda.kubernetes.api.model.*;
// import jenkins.model.JenkinsLocationConfiguration;
// import org.jenkinsci.plugins.workflow.job.WorkflowJob;
// import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;
// import org.junit.Rule;
// import org.junit.Test;
// import org.jvnet.hudson.test.WithoutJenkins;
//
// import java.io.IOException;
// import java.util.ArrayList;
// import java.util.Collections;
// import java.util.List;
// import java.util.Map;
//
// import static io.alauda.jenkins.devops.sync.constants.Constants.*;
// import static org.junit.Assert.*;
//
// public class JenkinsUtilsTest {
//    @Rule
//    public JenkinsK8sRule j = new JenkinsK8sRule();
//
//    @Test
//    @WithoutK8s
//    public void getItem() throws Exception {
//        FreeStyleProject freeStyleProject = j.createFreeStyleProject();
//
//        assertNotNull("can't found job", JenkinsUtils.getItem(freeStyleProject.getName()));
//        assertNull(JenkinsUtils.getItem("hello"));
//
//        // deleteRun
//        final WorkflowJob wfJob = j.createProject(WorkflowJob.class);
//        wfJob.scheduleBuild();
//        j.waitUntilNoActivity();
//        JenkinsUtils.deleteRun(wfJob.getLastBuild());
//        assertNull(wfJob.getLastBuild());
//
//        // empty params
//        List<V1alpha1PipelineParameter> params = new ArrayList<>();
//        assertNull(JenkinsUtils.addJobParamForPipelineParameters(wfJob, params, true));
//
//        // not support param type
//        params = new ArrayList<>();
//        params.add(new V1alpha1PipelineParameterBuilder()
//
// .withType("not-support").withName("name").withValue("value").withDescription("desc")
//                .build());
//        Map<String, ParameterDefinition> resultParams =
// JenkinsUtils.addJobParamForPipelineParameters(wfJob, params, true);
//        assertNotNull(resultParams);
//        assertEquals(0, resultParams.size());
//    }
//
//    @Test
//    @WithoutK8s
//    public void getRootUrl() {
//        assertNotNull(JenkinsUtils.getRootUrl());
//
//        JenkinsLocationConfiguration config = JenkinsLocationConfiguration.get();
//        assertNotNull(config);
//        config.setUrl("");
//
//        assertEquals(ROOT_URL, JenkinsUtils.getRootUrl());
//
//        config.setUrl(" ");
//        assertEquals(" /", JenkinsUtils.getRootUrl());
//    }
//
//    @Test
//    public void getBuildConfigName() throws InterruptedException, IOException {
//        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient(), "sleep
// 9999");
//        assertNotNull(config);
//
//        final String configName = config.getMetadata().getName();
//        WorkflowJob job = JobUtils.findWorkflowJob(j.jenkins,
//                config.getMetadata().getNamespace(), configName);
//        assertNotNull(JenkinsUtils.getBuildConfigName(job));
//        assertNotNull(JenkinsUtils.getFullJobName(job));
//
//        // deleteRun
//        Pipeline pipeline = j.getDevOpsInit().createPipeline(j.getClient(), configName);
//        assertNotNull(pipeline);
//        Thread.sleep(1000);
//
//        assertNotNull(JenkinsUtils.getJobFromPipeline(pipeline));
//
//        JenkinsUtils.cancelPipeline(job, pipeline);
//        JenkinsUtils.cancelPipeline(job, pipeline, true);
//        JenkinsUtils.cancelQueuedBuilds(job, "fake");
//
//        JenkinsUtils.deleteRun(job, pipeline);
//        assertNull(j.getDevOpsInit().getPipeline(j.getClient(),
// pipeline.getMetadata().getName()));
//
//        // not synced workflowJob
//        WorkflowJob wf = j.createProject(WorkflowJob.class);
//        JenkinsUtils.maybeScheduleNext(wf);
//    }
//
//    @Test
//    @WithoutJenkins
//    @WithoutK8s
//    public void filterNew() {
//        assertNull(JenkinsUtils.filterNew(null));
//
//        PipelineList list = new PipelineList();
//        assertNotNull(JenkinsUtils.filterNew(list));
//
//        list.setItems(new ArrayList<>());
//        assertNotNull(JenkinsUtils.filterNew(list));
//        assertEquals(0, JenkinsUtils.filterNew(list).getItems().size());
//
//        // one of them is null
//        assertNull(JenkinsUtils.putJobRunParamsFromEnvAndUIParams(Collections.EMPTY_LIST, null));
//        assertNotNull(JenkinsUtils.putJobRunParamsFromEnvAndUIParams(null,
// Collections.EMPTY_LIST));
//
//        List<Action> buildActions = new ArrayList<>();
//        // empty
//        List<PipelineParameter> pipelineParameters = new ArrayList<>();
//        assertNotNull(JenkinsUtils.putJobRunParamsFromEnvAndUIParams(pipelineParameters,
// buildActions));
//
//        // not supported type
//        pipelineParameters.add(new PipelineParameter());
//        assertNotNull(JenkinsUtils.putJobRunParamsFromEnvAndUIParams(pipelineParameters,
// buildActions));
//
//        // supported type
//        pipelineParameters.add(new PipelineParameter("", "WatcherAliveCheck",
// PIPELINE_PARAMETER_TYPE_STRING, "WatcherAliveCheck"));
//        buildActions = JenkinsUtils.putJobRunParamsFromEnvAndUIParams(pipelineParameters,
// buildActions);
//        assertNotNull(buildActions);
//        assertEquals(1, buildActions.size());
//    }
//
////    @Test
////    @WithoutK8s
////    public void addJobParamForBuildEnvs() throws IOException {
////        WorkflowJob wfJob = j.jenkins.createProject(WorkflowJob.class, "j");
////
////        JenkinsPipelineBuildStrategy strategy = new JenkinsPipelineBuildStrategy();
////        assertNull(JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, false));
////
////        // with env
////        final String envName = "name";
////        final String envNameVal = "jack";
////        strategy = new JenkinsPipelineBuildStrategyBuilder()
////                .addNewEnv().withName(envName)
////                .withValue(envNameVal).endEnv().build();
////
////        {
////            // not replace exists
////            Map<String, ParameterDefinition> defMap =
// JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, false);
////            assertNotNull(defMap);
////            assertEquals(1, defMap.size());
////
////            ParametersDefinitionProperty paramDefProperty =
// wfJob.getProperty(ParametersDefinitionProperty.class);
////            assertNotNull(paramDefProperty);
////
////            ParameterDefinition def = paramDefProperty.getParameterDefinition(envName);
////            assertNotNull(def);
////            assertEquals("wrong with ParameterDefinition", StringParameterDefinition.class,
////                    def.getClass());
////            assertEquals("wrong with value of parameters", envNameVal,
////                    ((StringParameterDefinition) def).getDefaultValue());
////            assertEquals("wrong with description", PARAM_FROM_ENV_DESCRIPTION,
// def.getDescription());
////        }
////
////        {
////            // replace exists
////            Map<String, ParameterDefinition> defMap =
// JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, true);
////            assertNotNull(defMap);
////            assertEquals(1, defMap.size());
////        }
////
////        // more env
////        strategy = new JenkinsPipelineBuildStrategyBuilder()
////                .addNewEnv().withName("age")
////                .withValue("12").endEnv().build();
////
////        {
////            // not replace exists
////            Map<String, ParameterDefinition> defMap =
// JenkinsUtils.addJobParamForBuildEnvs(wfJob, strategy, false);
////            assertNotNull(defMap);
////            assertEquals(2, defMap.size());
////
////            ParametersDefinitionProperty defPro =
// wfJob.getProperty(ParametersDefinitionProperty.class);
////            assertNotNull(defPro);
////            List<ParameterDefinition> defs = defPro.getParameterDefinitions();
////            assertNotNull(defs);
////            assertEquals(2, defs.size());
////        }
////    }
//
//    @Test
//    @WithoutK8s
//    public void setJobTriggers() throws IOException, ANTLRException {
//        WorkflowJob wfJob = j.jenkins.createProject(WorkflowJob.class, "p");
//        wfJob.addTrigger(new SCMTrigger("* * * * *"));
//
//        List<ANTLRException> exs = JenkinsUtils.setJobTriggers(wfJob, null);
//        assertNotNull(exs);
//        assertEquals(0, exs.size());
//
//        PipelineTriggersJobProperty triggerPro =
// wfJob.getProperty(PipelineTriggersJobProperty.class);
//        assertNotNull(triggerPro);
//        assertNotNull(triggerPro.getTriggers());
//        assertEquals(1, triggerPro.getTriggers().size());
//
//        // with wrong triggers
//        List<PipelineTrigger> triggers = new ArrayList<>();
//        triggers.add(new PipelineTrigger());
//        exs = JenkinsUtils.setJobTriggers(wfJob, triggers);
//        assertNotNull(exs);
//        assertEquals(0, exs.size());
//        triggerPro = wfJob.getProperty(PipelineTriggersJobProperty.class);
//        assertNull(triggerPro);
//
//        // with correct cron and scm triggers
//        triggers.add(new PipelineTrigger(null,
//                new PipelineTriggerCron(true, "* * * * *", null), PIPELINE_TRIGGER_TYPE_CRON));
//        triggers.add(new PipelineTrigger(null,
//                new PipelineTriggerCron(false, "* * * * *", null), PIPELINE_TRIGGER_TYPE_CRON));
//        triggers.add(new PipelineTrigger(new PipelineTriggerCodeChange(true, "* * * * *"),
//                null, PIPELINE_TRIGGER_TYPE_CODE_CHANGE));
//        triggers.add(new PipelineTrigger(new PipelineTriggerCodeChange(false, "* * * * *"),
//                null, PIPELINE_TRIGGER_TYPE_CODE_CHANGE));
//        exs = JenkinsUtils.setJobTriggers(wfJob, triggers);
//        assertEquals(0, exs.size());
//        triggerPro = wfJob.getProperty(PipelineTriggersJobProperty.class);
//        assertEquals(2, triggerPro.getTriggers().size());
//
//        // with invalid cron
//        triggers.add(new PipelineTrigger(null,
//                new PipelineTriggerCron(true, "abc", null), PIPELINE_TRIGGER_TYPE_CRON));
//        triggers.add(new PipelineTrigger(new PipelineTriggerCodeChange(true, "abc"),
//                null, PIPELINE_TRIGGER_TYPE_CODE_CHANGE));
//        triggers.add(new PipelineTrigger(null,
//                new PipelineTriggerCron(true, "abc", null), "fake"));
//        exs = JenkinsUtils.setJobTriggers(wfJob, triggers);
//        assertEquals(2, exs.size());
//    }
//
//    @Test
//    @WithoutK8s
//    @WithoutJenkins
//    public void getParameterValues() {
//        List<ParameterValue> values = JenkinsUtils.getParameterValues(null);
//        assertNotNull(values);
//        assertEquals(0, values.size());
//
//        // PIPELINE_PARAMETER_TYPE_BOOLEAN
//        List<PipelineParameter> pipelineParameters = new ArrayList<>();
//        pipelineParameters.add(new
// PipelineParameterBuilder().withType(PIPELINE_PARAMETER_TYPE_BOOLEAN)
//                .withName("name").withValue("value").build());
//        pipelineParameters.add(new PipelineParameterBuilder().withType("fake")
//                .withName("name").withValue("value").build());
//        values = JenkinsUtils.getParameterValues(pipelineParameters);
//        assertEquals(1, values.size());
//    }
// }
