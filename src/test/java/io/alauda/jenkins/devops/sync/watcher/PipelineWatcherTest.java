package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.Run;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.DevOpsInit;
import io.alauda.jenkins.devops.sync.GlobalPluginConfiguration;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

import static io.alauda.jenkins.devops.sync.util.JobUtils.findWorkflowJob;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class PipelineWatcherTest {
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
    public void triggerPipeline() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        final String folderName = devOpsInit.getNamespace();
        final String jobName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, jobName);

        // check pipeline trigger
        for(int i = 1; i < 4; i++) {
            trigger(workflowJob);
            Run build = workflowJob.getBuildByNumber(i);
            assertNotNull(build);
        }
    }

    @Test
    public void deletePipeline() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        final String folderName = devOpsInit.getNamespace();
        final String jobName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, jobName);

        for (int i = 0; i < 3; i++) {
            trigger(workflowJob);
            WorkflowRun lastBuild = workflowJob.getLastBuild();
            assertNotNull(lastBuild);
            lastBuild.delete();
            assertEquals(0, devOpsInit.getPipelines(client).size());
        }
    }


    private void trigger(WorkflowJob workflowJob) throws Exception {
        Pipeline pipeline = devOpsInit.createPipeline(client, workflowJob.getName());
        assertNotNull(pipeline);
        Thread.sleep(3000);
        j.waitUntilNoActivity();
    }

    @After
    public void tearDown() throws IOException {
        devOpsInit.close();
    }
}