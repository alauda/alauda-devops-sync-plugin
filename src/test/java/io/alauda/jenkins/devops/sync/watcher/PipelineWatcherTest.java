package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.Result;
import hudson.model.Run;
import io.alauda.jenkins.devops.sync.ActivityWaitException;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static io.alauda.jenkins.devops.sync.constants.Constants.ANNOTATION_BADGE;
import static io.alauda.jenkins.devops.sync.util.JobUtils.findWorkflowJob;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

public class PipelineWatcherTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void triggerPipeline() throws Exception {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        final String folderName = j.getDevOpsInit().getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);

        // check pipeline trigger
        for(int i = 1; i < j.getRetryCount(); i++) {
            trigger(pipCfgName);
            Run build = workflowJob.getBuildByNumber(i);
            assertNotNull(build);

            j.waitForCompletion(build);
            List<Pipeline> pipelineList = j.getDevOpsInit().getPipelines(j.getClient());
            assertEquals(PipelinePhases.COMPLETE, pipelineList.get(i - 1).getStatus().getPhase());
        }
    }

    @Test
    public void wrongJenkinsfile() throws Exception, ActivityWaitException {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient(), "asf");
        final String folderName = j.getDevOpsInit().getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);
        trigger(pipCfgName);
        Run build = workflowJob.getBuildByNumber(1);
        assertNotNull(build);
        j.waitForCompletion(build);

        assertEquals(Result.FAILURE, build.getResult());
        Thread.sleep(3000);
        List<Pipeline> pipelineList = j.getDevOpsInit().getPipelines(j.getClient());
        assertEquals(1, pipelineList.size());
        assertEquals(PipelinePhases.FAILED, pipelineList.get(0).getStatus().getPhase());
    }

    @Test
    public void deletePipeline() throws Exception, ActivityWaitException {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        final String folderName = j.getDevOpsInit().getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);

        for (int i = 0; i < j.getRetryCount(); i++) {
            trigger(pipCfgName);
            WorkflowRun lastBuild = workflowJob.getLastBuild();
            assertNotNull(lastBuild);
            lastBuild.delete();
            assertEquals(0, j.getDevOpsInit().getPipelines(j.getClient()).size());
        }
    }

    @Test
    public void cancelPipeline() throws Exception, ActivityWaitException {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient(), "sleep 9999");
        final String folderName = j.getDevOpsInit().getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        Pipeline pipeline = trigger(pipCfgName, false);
        assertNotNull(pipeline);
        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);

        // ensure job is running
        WorkflowRun build = ensureRunning(workflowJob, 1);

        build.doKill();
        Thread.sleep(3000);

        final String pipelineName = pipeline.getMetadata().getName();
        pipeline = j.getDevOpsInit().getPipeline(j.getClient(), pipelineName);
        assertNotNull(String.format("no pipeline[%s], in namespace[%s]", pipelineName, folderName), pipeline);
        assertEquals("cancel job failed", PipelinePhases.CANCELLED, pipeline.getStatus().getPhase());

        // check point, cancel from k8s
        pipeline = trigger(pipCfgName, false);
        build = ensureRunning(workflowJob, 2);
        j.getDevOpsInit().abortPipeline(j.getClient(), pipeline.getMetadata().getName());
        Thread.sleep(3000);
        assertFalse("cancel job failed", build.isBuilding());
        assertEquals(Result.ABORTED, build.getResult());
    }

    @Test
    public void testBadge() throws InterruptedException {
        final String badgeId = "id";
        final String badgeText = "text";
        final String jenkinsFile = "addErrorBadge id: '" + badgeId + "', text: '" + badgeText + "'";
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient(), jenkinsFile);
        final String folderName = j.getDevOpsInit().getNamespace();
        final String pipCfgName = config.getMetadata().getName();

        WorkflowJob workflowJob = findWorkflowJob(j.jenkins, folderName, pipCfgName);
        trigger(pipCfgName);
        Run build = workflowJob.getBuildByNumber(1);
        assertNotNull(build);
        j.waitForCompletion(build);

        assertEquals(Result.SUCCESS, build.getResult());
        Thread.sleep(3000);
        List<Pipeline> pipelineList = j.getDevOpsInit().getPipelines(j.getClient());
        assertEquals(1, pipelineList.size());

        final Pipeline pipeline = pipelineList.get(0);
        assertEquals(PipelinePhases.COMPLETE, pipeline.getStatus().getPhase());

        Map<String, String> annotations = pipeline.getMetadata().getAnnotations();
        assertNotNull(annotations);

        String annotationBadges = annotations.get(ANNOTATION_BADGE);
        assertNotNull(annotationBadges);

        assertThat(annotationBadges, containsString(badgeText));
    }

    private WorkflowRun ensureRunning(WorkflowJob workflowJob, int num) throws InterruptedException, IOException {
        WorkflowRun build = null;
        for(int i = 0; i < j.getRetryCount(); i++) {
            build = workflowJob.getLastBuild();
            if(build != null && build.getNumber() == num && build.isBuilding()) {
                break;
            }

            Thread.sleep(1000);
        }
        assertNotNull(build);

        // check null just for sonar rules
        if(build == null) {
            return null;
        }

        assertTrue(build.getLog(), build.isBuilding());
        return build;
    }

    private Pipeline trigger(String pipelineConfigName) throws InterruptedException, ActivityWaitException {
        return trigger(pipelineConfigName, true);
    }

    private Pipeline trigger(String pipelineConfigName, boolean wait) throws ActivityWaitException, InterruptedException {
        Pipeline pipeline = j.getDevOpsInit().createPipeline(j.getClient(), pipelineConfigName);
        assertNotNull(pipeline);
        Thread.sleep(3000);
        if(wait) {
            try {
                j.waitUntilNoActivity();
            } catch (Exception e) {
                throw new ActivityWaitException("Jenkins wait activity error.", e);
            }
        }
        return pipeline;
    }
}