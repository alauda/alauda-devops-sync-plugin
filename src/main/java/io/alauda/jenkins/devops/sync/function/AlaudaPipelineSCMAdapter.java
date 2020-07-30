package io.alauda.jenkins.devops.sync.function;

import hudson.model.TopLevelItem;
import hudson.plugins.git.GitSCM;
import hudson.scm.SCM;
import io.alauda.jenkins.devops.sync.scm.RecordLastChangeLog;
import java.io.IOException;
import java.util.function.Consumer;
import org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlaudaPipelineSCMAdapter implements Consumer<TopLevelItem> {
  private static final Logger logger = LoggerFactory.getLogger(AlaudaPipelineSCMAdapter.class);

  @Override
  public void accept(TopLevelItem item) {
    WorkflowJob wfJob = (WorkflowJob) item;

    FlowDefinition def = wfJob.getDefinition();
    if (def instanceof CpsScmFlowDefinition) {
      CpsScmFlowDefinition scmDef = (CpsScmFlowDefinition) def;
      SCM scm = scmDef.getScm();
      GitSCM gitSCM = (GitSCM) scm;
      gitSCM.getExtensions().setOwner(wfJob);
      try {
        gitSCM.getExtensions().replace(new RecordLastChangeLog());
      } catch (IOException e) {
        logger.error("cannot save WorkflowJob " + wfJob.getFullName(), e);
      }

      logger.info("add extension for job: " + wfJob.getFullName());
    }
  }
}
