package io.alauda.jenkins.devops.sync.watcher;

import hudson.BulkChange;
import hudson.model.*;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.DevOpsInit;
import io.alauda.jenkins.devops.sync.GlobalPluginConfiguration;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineParameter;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.alauda.jenkins.devops.sync.util.JobUtils.findJob;
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
//        assertTrue(buf.toString().contains(rondomName));
        assertThat(buf.toString(), containsString(randomName));

        // change params
        String paramRelease = "isRelease";
        paramMap.put(paramRelease, "boolean"); // the value should be type of param in here
        paramMap.put(paramName, "string");
        devOpsInit.updatePipelineConfigWithParams(client, jobName, paramMap, script);
        jobItem = findJob(j.jenkins, folderName, jobName);

        assertEquals(WorkflowJob.class, jobItem.getClass());
        WorkflowJob wfJob = (WorkflowJob) jobItem;
        ParametersDefinitionProperty paramDefPro = wfJob.getProperty(ParametersDefinitionProperty.class);
        assertNotNull(paramDefPro);
        assertNotNull(paramDefPro.getParameterDefinition(paramRelease));
        assertEquals(paramDefPro.getParameterDefinition(paramRelease).getClass(), BooleanParameterDefinition.class);
        assertNotNull(paramDefPro.getParameterDefinition(paramName));
        assertEquals(paramDefPro.getParameterDefinition(paramName).getClass(), StringParameterDefinition.class);

        {
            // round trip for change params
            paramDefPro = wfJob.getProperty(ParametersDefinitionProperty.class);
            final String paramOther = "other";
            final String paramOtherValue = "other-value";
            BulkChange bk = new BulkChange(wfJob);
            StringParameterDefinition paramDef = new StringParameterDefinition(paramOther, paramOtherValue);
            List<ParameterDefinition> paramDefs = paramDefPro.getParameterDefinitions();
            paramDefs.add(paramDef);
            wfJob.save();
            bk.commit();

            Thread.sleep(3000); // TODO should use better way to make sure synced
            PipelineConfig pipelineConfig = devOpsInit.getPipelineConfig(client, jobName);
            assertNotNull(pipelineConfig);
            List<PipelineParameter> parameters = pipelineConfig.getSpec().getParameters();
            assertNotNull(parameters);
            assertEquals(paramDefs.size(), parameters.size());
        }
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
