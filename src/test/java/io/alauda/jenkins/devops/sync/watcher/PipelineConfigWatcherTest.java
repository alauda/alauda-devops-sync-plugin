package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.*;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.DevOpsInit;
import io.alauda.jenkins.devops.sync.GlobalPluginConfiguration;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.JobUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.alauda.kubernetes.api.model.*;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.alauda.jenkins.devops.sync.util.JobUtils.findJob;
import static io.alauda.jenkins.devops.sync.util.JobUtils.findWorkflowJob;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

public class PipelineConfigWatcherTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();
    private AlaudaDevOpsClient client;
    private DevOpsInit devOpsInit;

    @Before
    public void setup() throws IOException {
        devOpsInit = new DevOpsInit().init();
        client = devOpsInit.getClient();
        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
        config.setJenkinsService(devOpsInit.getJenkinsName());
        config.configChange();
    }

    @Test
    public void simpleJobSync() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        final String folderName = devOpsInit.getNamespace();
        final String jobName = config.getMetadata().getName();

        Job jobItem = findJob(j.jenkins, folderName, jobName);
        assertNotNull(jobItem);
        assertEquals(jobItem.getClass(), WorkflowJob.class);

        WorkflowJob workflowJob = (WorkflowJob) jobItem;
        assertEquals(workflowJob.isConcurrentBuild(), PipelineConfigUtils.isParallel(config));
        assertNotEquals(workflowJob.isConcurrentBuild(), PipelineConfigUtils.isSerialPolicy(config));

        // check pipeline trigger
        Pipeline pipeline = devOpsInit.createPipeline(client, jobName);
        assertNotNull(pipeline);
        Thread.sleep(3000);
        j.waitUntilNoActivity();
        Run build = jobItem.getBuildByNumber(1);
        assertNotNull(build);

        // check pipeline update
        String script = "echo '1'";
        devOpsInit.updatePipelineConfig(client, jobName, script);
        CpsFlowDefinition cpsFlowDefinition = getCpsFlowDefinition(folderName, jobName);
        assertEquals("jenkinsfile update failed", script, cpsFlowDefinition.getScript());

        // check pipeline trigger after update jenkinsfile
        pipeline = devOpsInit.createPipeline(client, jobName);
        assertNotNull(pipeline);
        Thread.sleep(3000);
        j.waitUntilNoActivity();
        build = jobItem.getBuildByNumber(2);
        assertNotNull(build);
        assertEquals(Result.SUCCESS, build.getResult());
    }

    @Test
    public void deletePipelineConfig() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        final String folderName = devOpsInit.getNamespace();
        String jobName = config.getMetadata().getName();

        final Job job = JobUtils.findJob(j.jenkins, folderName, jobName);
        assertNotNull(job);

        // delete from jenkins
        job.delete();
        assertNull(devOpsInit.getPipelineConfig(client, jobName));

        // delete from k8s
        config = devOpsInit.createPipelineConfig(client);
        jobName = config.getMetadata().getName();
        assertNotNull(JobUtils.findJob(j.jenkins, folderName, jobName));

        devOpsInit.deletePipelineConfig(client, jobName);
        Thread.sleep(3000);
        assertNull(JobUtils.findJob(j.jenkins, folderName, jobName));
    }

    @Test
    public void parameterizedJobSync() throws Exception {
        final String paramName = "name";
        final String script = "echo env." + paramName;
        final Map<String, String> paramMap = new HashMap<>();
        paramMap.put(paramName, "string");

        PipelineConfig config = devOpsInit.createPipelineConfigWithParams(client, paramMap, script);
        final String folderName = devOpsInit.getNamespace();
        final String jobName = config.getMetadata().getName();

        Job jobItem = findJob(j.jenkins, folderName, jobName);
        final String randomName = System.currentTimeMillis() + "-alauda";
        paramMap.put(paramName, randomName);
        devOpsInit.createPipeline(client, jobName, paramMap);
        Thread.sleep(3000);
        j.waitUntilNoActivity();

        Run build = jobItem.getBuildByNumber(1);
        assertNotNull(build);
        assertEquals(Result.SUCCESS, build.getResult());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        build.getLogText().writeRawLogTo(0, buf);
        assertThat(buf.toString(), containsString(randomName));

        // change params
        String paramRelease = "isRelease";
        paramMap.put(paramRelease, "boolean"); // the value should be type of param in here
        paramMap.put(paramName, "string");
        config = devOpsInit.updatePipelineConfigWithParams(client, jobName, paramMap, script);
        assertNotNull(config);
        Thread.sleep(2000);

        WorkflowJob wfJob = findWorkflowJob(j.jenkins, folderName, jobName);
        ParametersDefinitionProperty paramDefPro = wfJob.getProperty(ParametersDefinitionProperty.class);
        assertNotNull(paramDefPro);
        assertNotNull("parameter sync failed", paramDefPro.getParameterDefinition(paramRelease));
        assertEquals(paramDefPro.getParameterDefinition(paramRelease).getClass(), BooleanParameterDefinition.class);
        assertNotNull(paramDefPro.getParameterDefinition(paramName));
        assertEquals(paramDefPro.getParameterDefinition(paramName).getClass(), StringParameterDefinition.class);

        {
            // round trip for change params
            wfJob = findWorkflowJob(j.jenkins, folderName, jobName);
            paramDefPro = wfJob.getProperty(ParametersDefinitionProperty.class);
            wfJob.removeProperty(ParametersDefinitionProperty.class);
            final String paramOther = "other";
            final String paramOtherValue = "other-value";
            List<ParameterDefinition> paramDefs = paramDefPro.getParameterDefinitions();
            paramDefs.add(new StringParameterDefinition(paramOther, paramOtherValue));


            JenkinsUtils.addJobParamForPipelineParameters(wfJob, null, true);
            wfJob.removeProperty(ParametersDefinitionProperty.class);
            wfJob.addProperty(new ParametersDefinitionProperty(paramDefs));
            ItemListener.fireOnUpdated(wfJob);

            Thread.sleep(3000); // TODO should use better way to make sure synced
            wfJob = findWorkflowJob(j.jenkins, folderName, jobName);
            paramDefPro = wfJob.getProperty(ParametersDefinitionProperty.class);
            paramDefs = paramDefPro.getParameterDefinitions();

            PipelineConfig pipelineConfig = devOpsInit.getPipelineConfig(client, jobName);
            assertNotNull(pipelineConfig);
            List<PipelineParameter> parameters = pipelineConfig.getSpec().getParameters();
            assertNotNull(parameters);
            assertEquals(paramDefs.size(), parameters.size());
        }
    }

    @Test
    public void triggerSync() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        final String folderName = devOpsInit.getNamespace();
        final String jobName = config.getMetadata().getName();

        WorkflowJob job = JobUtils.findWorkflowJob(j.jenkins, folderName, jobName);

        List<Trigger<?>> triggers = new ArrayList<>();
        triggers.add(new SCMTrigger("* * * * *"));
        triggers.add(new TimerTrigger("* * * * *"));
        job.setTriggers(triggers);
        ItemListener.fireOnUpdated(job);

        Thread.sleep(3000);
        config = devOpsInit.getPipelineConfig(client, jobName);
        List<PipelineTrigger> k8sTriggers = config.getSpec().getTriggers();
        assertNotNull(k8sTriggers);
        assertEquals(triggers.size(), k8sTriggers.size());

        // add triggers from k8s
        final String cron = "* * * * *";
        config = devOpsInit.createPipelineConfig(client);
        devOpsInit.addCronTrigger4PipelineConfig(client, config.getMetadata().getName(), cron);
        Thread.sleep(3000);
        job = JobUtils.findWorkflowJob(j.jenkins, folderName, config.getMetadata().getName());
        assertNotNull(job);
        assertEquals(1, job.getTriggers().size());
        assertEquals(cron, job.getTriggers().values().toArray(new Trigger[]{})[0].getSpec());
    }

    private CpsFlowDefinition getCpsFlowDefinition(String folderName, String jobName) throws Exception {
        Thread.sleep(3000);
        j.waitUntilNoActivity();
        Job jobItem = findJob(j.jenkins, folderName, jobName);
        assertNotNull(jobItem);
        assertEquals(jobItem.getClass(), WorkflowJob.class);

        WorkflowJob workflowJob = (WorkflowJob) jobItem;
        FlowDefinition workflowDef = workflowJob.getDefinition();
        assertEquals(workflowDef.getClass(), CpsFlowDefinition.class);

        return (CpsFlowDefinition) workflowDef;
    }

    @After
    public void tearDown() throws IOException {
        devOpsInit.close();
    }
}
