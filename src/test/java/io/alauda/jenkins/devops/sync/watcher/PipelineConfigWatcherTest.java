package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.*;
import hudson.model.Job;
import hudson.model.listeners.ItemListener;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.JobUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.alauda.kubernetes.api.model.*;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_PARAMETER_TYPE_BOOLEAN;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_PARAMETER_TYPE_STRING;
import static io.alauda.jenkins.devops.sync.util.JobUtils.findJob;
import static io.alauda.jenkins.devops.sync.util.JobUtils.findWorkflowJob;
import static org.hamcrest.Matchers.containsString;
import static org.junit.Assert.*;

public class PipelineConfigWatcherTest {

    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void simpleJobSync() throws Exception {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        final String folderName = j.getDevOpsInit().getNamespace();
        final String jobName = config.getMetadata().getName();

        Job jobItem = findJob(j.jenkins, folderName, jobName);
        assertNotNull(jobItem);
        assertEquals(jobItem.getClass(), WorkflowJob.class);

        WorkflowJob workflowJob = (WorkflowJob) jobItem;
        assertEquals(workflowJob.isConcurrentBuild(), PipelineConfigUtils.isParallel(config));
        assertNotEquals(workflowJob.isConcurrentBuild(), PipelineConfigUtils.isSerialPolicy(config));

        // check pipeline run
        Pipeline pipeline = j.getDevOpsInit().createPipeline(j.getClient(), jobName);
        assertNotNull(pipeline);
        Thread.sleep(3000);
        j.waitUntilNoActivity();
        Run build = jobItem.getBuildByNumber(1);
        assertNotNull(build);

        // check pipeline update
        String script = "echo '1'";
        j.getDevOpsInit().updatePipelineConfig(j.getClient(), jobName, script);
        CpsFlowDefinition cpsFlowDefinition = getCpsFlowDefinition(folderName, jobName);
        assertEquals("jenkinsfile update failed", script, cpsFlowDefinition.getScript());

        // check pipeline run after update jenkinsfile
        pipeline = j.getDevOpsInit().createPipeline(j.getClient(), jobName);
        assertNotNull(pipeline);
        Thread.sleep(3000);
        j.waitUntilNoActivity();
        build = jobItem.getBuildByNumber(2);
        assertNotNull(build);
        assertEquals(Result.SUCCESS, build.getResult());
    }

    @Test
    public void scmPipeline() throws Exception {
        // lack git source info
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient(), null, "jenkinsfile", null);
        PipelineConfig targetConfig = assertPhase(config, PipelineConfigPhase.ERROR);
        PipelineConfigStatus status = targetConfig.getStatus();
        assertNotNull(status.getMessage());
        List<Condition> conditions = status.getConditions();
        assertEquals(1, conditions.size());
        conditions.forEach(condition -> {
            assertNotNull(condition.getMessage());
            assertNotNull(condition.getReason());
        });

        // correct pipeline
        config = j.getDevOpsInit().createPipelineConfig(j.getClient(), null, "jenkinsfile", null, j.getDevOpsInit().getSecretName());
        targetConfig = assertPhase(config, PipelineConfigPhase.READY);
        ObjectMeta meta = targetConfig.getMetadata();
        WorkflowJob wfJob = JobUtils.findWorkflowJob(j.jenkins, meta.getNamespace(), meta.getName());
        assertEquals(CpsScmFlowDefinition.class, wfJob.getDefinition().getClass());
    }

    @Test
    public void deletePipelineConfig() throws Exception {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        final String folderName = j.getDevOpsInit().getNamespace();
        String jobName = config.getMetadata().getName();

        final Job job = JobUtils.findJob(j.jenkins, folderName, jobName);
        assertNotNull(job);

        // delete from jenkins
        job.delete();
        untilNotExists(jobName);
        assertNull("pipeline can't be deleted", j.getDevOpsInit().getPipelineConfig(j.getClient(), jobName));

        // delete from k8s
        config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        jobName = config.getMetadata().getName();
        assertNotNull(JobUtils.findJob(j.jenkins, folderName, jobName));

        j.getDevOpsInit().deletePipelineConfig(j.getClient(), jobName);
        untilDeleted(folderName, jobName);
        assertNull(JobUtils.findJob(j.jenkins, folderName, jobName));
    }

    private void untilDeleted(String folderName, String jobName) throws InterruptedException {
        for(int i = 0; i < 4; i++) {
            Job job = JobUtils.findJob(j.jenkins, folderName, jobName);
            if(job == null) {
                return;
            }

            Thread.sleep(1000);
        }
    }

    /**
     * Return until target Pipeline is gone.
     * @param jobName piepline name
     * @throws InterruptedException in case of interrupted
     */
    private void untilNotExists(String jobName) throws InterruptedException {
        for(int i = 0; i < 4; i++) {
            PipelineConfig config = j.getDevOpsInit().getPipelineConfig(j.getClient(), jobName);
            if(config == null) {
                return;
            }

            Thread.sleep(1000);
        }
    }

    @Test
    public void parameterizedJobSync() throws Exception {
        final String paramName = "name";
        final String script = "echo env." + paramName;
        final Map<String, String> paramMap = new HashMap<>();
        paramMap.put(paramName, PIPELINE_PARAMETER_TYPE_STRING);

        PipelineConfig config = j.getDevOpsInit().createPipelineConfigWithParams(j.getClient(), paramMap, script);
        final String folderName = j.getDevOpsInit().getNamespace();
        final String jobName = config.getMetadata().getName();

        Job jobItem = findJob(j.jenkins, folderName, jobName);
        assertNotNull(jobItem);
        final String randomName = System.currentTimeMillis() + "-alauda";
        paramMap.put(paramName, randomName);
        j.getDevOpsInit().createPipeline(j.getClient(), jobName, paramMap);
        for(int i = 0; i < 4; i++) {
            if(jobItem.isBuilding()) {
                break;
            }
            Thread.sleep(1000);
        }
        j.waitUntilNoActivity();

        Run build = jobItem.getBuildByNumber(1);
        assertNotNull(build);
        assertEquals(Result.SUCCESS, build.getResult());
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        build.getLogText().writeRawLogTo(0, buf);
        assertThat(buf.toString(), containsString(randomName));

        // change params
        String paramRelease = "isRelease";
        paramMap.put(paramRelease, PIPELINE_PARAMETER_TYPE_BOOLEAN); // the value should be type of param in here
        paramMap.put(paramName, PIPELINE_PARAMETER_TYPE_STRING);
        config = j.getDevOpsInit().updatePipelineConfigWithParams(j.getClient(), jobName, paramMap, script);
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

            PipelineConfig pipelineConfig = j.getDevOpsInit().getPipelineConfig(j.getClient(), jobName);
            assertNotNull(pipelineConfig);
            List<PipelineParameter> parameters = pipelineConfig.getSpec().getParameters();
            assertNotNull(parameters);
            assertEquals(paramDefs.size(), parameters.size());
        }
    }

    @Test
    public void triggerSync() throws Exception {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        final String folderName = j.getDevOpsInit().getNamespace();
        final String jobName = config.getMetadata().getName();

        WorkflowJob job = JobUtils.findWorkflowJob(j.jenkins, folderName, jobName);

        List<Trigger<?>> triggers = new ArrayList<>();
        triggers.add(new SCMTrigger("* * * * *"));
        triggers.add(new TimerTrigger("* * * * *"));
        job.setTriggers(triggers);
        ItemListener.fireOnUpdated(job);

        Thread.sleep(3000);
        config = j.getDevOpsInit().getPipelineConfig(j.getClient(), jobName);
        List<PipelineTrigger> k8sTriggers = config.getSpec().getTriggers();
        assertNotNull(k8sTriggers);
        assertEquals(triggers.size(), k8sTriggers.size());

        // add triggers from k8s
        final String cron = "* * * * 1";
        config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        j.getDevOpsInit().addCronTrigger4PipelineConfig(j.getClient(), config.getMetadata().getName(), cron);
        assertPhase(config, PipelineConfigPhase.READY);
        job = JobUtils.findWorkflowJob(j.jenkins, folderName, config.getMetadata().getName());
        assertNotNull(job);
        assertEquals(1, job.getTriggers().size());
        assertEquals(cron, job.getTriggers().values().toArray(new Trigger[]{})[0].getSpec());

        // add invalid trigger from k8s
        final String invalidCron = "bad";
        config = j.getDevOpsInit().createPipelineConfig(j.getClient(), "echo '2'", invalidCron);
        config = assertPhase(config, PipelineConfigPhase.ERROR);
        assertNotNull(config.getStatus().getMessage());
        List<Condition> conditions = config.getStatus().getConditions();
        assertNotNull(conditions);
        assertEquals(1, conditions.size());
        conditions.forEach(condition -> assertNotNull(condition.getMessage()));
    }

    private PipelineConfig assertPhase(final PipelineConfig config, final String phase) throws InterruptedException {
        assertNotNull(config);
        String name = config.getMetadata().getName();

        PipelineConfig target = null;
        for(int i = 0; i < 8; i++) {
            target = j.getDevOpsInit().getPipelineConfig(j.getClient(), name);
            if(phase.equals(target.getStatus().getPhase())) {
                break;
            }
            Thread.sleep(1000);
        }

        if(target == null || target.getStatus() == null) {
            return target;
        }

        PipelineConfigStatus status = target.getStatus();
        StringBuffer buf = new StringBuffer(status.getMessage() != null ? status.getMessage(): "");
        if(status.getConditions() != null) {
            status.getConditions().forEach(condition -> {
                buf.append("\n").append(condition.getMessage());
            });
        }

        assertEquals(buf.toString(), phase, status.getPhase());

        return target;
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
}
