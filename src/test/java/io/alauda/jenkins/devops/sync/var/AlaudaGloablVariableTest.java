package io.alauda.jenkins.devops.sync.var;

import hudson.model.queue.QueueTaskFuture;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class AlaudaGloablVariableTest {
  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void name() throws Exception {
    WorkflowJob wf = j.createProject(WorkflowJob.class);
    wf.addProperty(
        new WorkflowJobProperty(
            "", "hello", "", "", "{\"alauda.io/pipelinecontext.project\":\"projectname\"}"));
    wf.setDefinition(new CpsFlowDefinition("echo alaudaContext.getItem(\"project\")"));

    QueueTaskFuture<WorkflowRun> run = wf.scheduleBuild2(0);
    j.assertBuildStatusSuccess(run);
    j.assertLogContains("projectname", run.waitForStart());
  }
}
