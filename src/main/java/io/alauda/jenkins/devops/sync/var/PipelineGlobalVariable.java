package io.alauda.jenkins.devops.sync.var;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

@Extension
public class PipelineGlobalVariable extends GlobalVariable {

  @Nonnull
  @Override
  public String getName() {
    return "alaudaPipeline";
  }

  @Nonnull
  @Override
  public Object getValue(@Nonnull CpsScript script) throws Exception {
    Run<?, ?> build = script.$build();
    if (build == null) {
      throw new IllegalStateException("cannot find owning build");
    }

    Job<?, ?> parent = build.getParent();
    if (parent instanceof WorkflowJob) {
      return new PipelineContext((WorkflowJob) parent);
    }
    throw new IllegalStateException("not instance of WorkflowJob");
  }
}
