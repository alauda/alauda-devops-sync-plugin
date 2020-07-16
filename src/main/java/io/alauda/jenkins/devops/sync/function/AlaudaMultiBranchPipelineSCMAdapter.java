package io.alauda.jenkins.devops.sync.function;

import hudson.model.TopLevelItem;
import io.alauda.jenkins.devops.sync.scm.RecordLastChangeLogTrait;
import java.io.IOException;
import java.util.List;
import java.util.function.Consumer;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AlaudaMultiBranchPipelineSCMAdapter implements Consumer<TopLevelItem> {
  private static final Logger logger =
      LoggerFactory.getLogger(AlaudaMultiBranchPipelineSCMAdapter.class);

  @Override
  public void accept(TopLevelItem item) {
    WorkflowMultiBranchProject project = (WorkflowMultiBranchProject) item;
    project
        .getSCMSources()
        .forEach(
            scmSource -> {
              List<SCMSourceTrait> traits = scmSource.getTraits();
              RecordLastChangeLogTrait trait = new RecordLastChangeLogTrait();
              if (traits != null && !scmSource.getTraits().contains(trait)) {
                scmSource.getTraits().add(trait);
              }
            });
    try {
      project.save();
    } catch (IOException e) {
      logger.error("cannot save WorkflowMultiBranchProject " + project.getFullName(), e);
    }
  }
}
