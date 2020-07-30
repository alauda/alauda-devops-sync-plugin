package io.alauda.jenkins.devops.sync.function;

import hudson.model.TopLevelItem;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import java.util.function.Predicate;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

public class AlaudaPipelineFilter implements Predicate<TopLevelItem> {

  @Override
  public boolean test(TopLevelItem item) {
    if (!(item instanceof WorkflowJob)) {
      return false;
    }

    WorkflowJobProperty pro = WorkflowJobUtils.getAlaudaProperty((WorkflowJob) item);
    return pro != null && pro.isValid();
  }
}
