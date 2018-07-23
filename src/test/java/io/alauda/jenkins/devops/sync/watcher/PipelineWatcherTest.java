package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.Result;
import hudson.model.Run;
import hudson.model.queue.QueueTaskFuture;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.util.DevOpsInit;
import io.alauda.jenkins.devops.sync.GlobalPluginConfiguration;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.*;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.TestExtension;

import java.io.IOException;
import java.util.List;

import static io.alauda.jenkins.devops.sync.util.JobUtils.findWorkflowJob;
import static org.junit.Assert.*;

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
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);

        // check pipeline trigger
        for(int i = 1; i < 4; i++) {
            trigger(pipCfgName);
            Run build = workflowJob.getBuildByNumber(i);
            assertNotNull(build);

            j.waitForCompletion(build);
            List<Pipeline> pipelineList = devOpsInit.getPipelines(client);
            assertEquals(PipelinePhases.COMPLETE, pipelineList.get(i - 1).getStatus().getPhase());
        }
    }

    @Test
    public void wrongJenkinsfile() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client, "asf");
        final String folderName = devOpsInit.getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);
        trigger(pipCfgName);
        Run build = workflowJob.getBuildByNumber(1);
        assertNotNull(build);
        j.waitForCompletion(build);

        assertEquals(Result.FAILURE, build.getResult());
        Thread.sleep(3000);
        List<Pipeline> pipelineList = devOpsInit.getPipelines(client);
        assertEquals(1, pipelineList.size());
        assertEquals(PipelinePhases.FAILED, pipelineList.get(0).getStatus().getPhase());
    }

    @Test
    public void deletePipeline() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        final String folderName = devOpsInit.getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);

        for (int i = 0; i < 3; i++) {
            trigger(pipCfgName);
            WorkflowRun lastBuild = workflowJob.getLastBuild();
            assertNotNull(lastBuild);
            lastBuild.delete();
            assertEquals(0, devOpsInit.getPipelines(client).size());
        }
    }

    @Test
    public void cancelPipeline() throws Exception {
        PipelineConfig config = devOpsInit.createPipelineConfig(client, "sleep 9999");
        final String folderName = devOpsInit.getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        Pipeline pipeline = trigger(pipCfgName, false);
        assertNotNull(pipeline);
        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);

        // ensure job is running
        WorkflowRun build = ensureRunning(workflowJob, 1);

        build.doKill();
        Thread.sleep(3000);

        final String pipelineName = pipeline.getMetadata().getName();
        pipeline = devOpsInit.getPipeline(client, pipelineName);
        assertNotNull(String.format("no pipeline[%s], in namespace[%s]", pipelineName, folderName), pipeline);
        assertEquals("cancel job failed", PipelinePhases.CANCELLED, pipeline.getStatus().getPhase());

        // check point, cancel from k8s
        pipeline = trigger(pipCfgName, false);
        build = ensureRunning(workflowJob, 2);
        devOpsInit.abortPipeline(client, pipeline.getMetadata().getName());
        Thread.sleep(3000);
        assertFalse("cancel job failed", build.isBuilding());
        assertEquals(Result.ABORTED, build.getResult());
    }

    private WorkflowRun ensureRunning(WorkflowJob workflowJob, int num) throws InterruptedException, IOException {
        WorkflowRun build = null;
        for(int i = 0; i < 3; i++) {
            build = workflowJob.getLastBuild();
            if(build != null && build.getNumber() == num && build.isBuilding()) {
                break;
            }

            Thread.sleep(1000);
        }
        assertNotNull(build);
        assertTrue(build.getLog(), build.isBuilding());
        return build;
    }

    private Pipeline trigger(String pipelineConfigName) throws Exception {
        return trigger(pipelineConfigName, true);
    }

    private Pipeline trigger(String pipelineConfigName, boolean wait) throws Exception {
        Pipeline pipeline = devOpsInit.createPipeline(client, pipelineConfigName);
        assertNotNull(pipeline);
        Thread.sleep(3000);
        if(wait) {
            j.waitUntilNoActivity();
        }
        return pipeline;
    }

    @After
    public void tearDown() throws IOException {
        devOpsInit.close();
    }
}