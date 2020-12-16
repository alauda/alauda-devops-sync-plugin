package io.alauda.jenkins.devops.sync.tasks.once;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Item;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

/** This Task will disable resume for all previous created jobs created by PipelineConfig. */
@Extension
public class MigrateResumePropertyTask {

  private final JenkinsClient jenkinsClient = JenkinsClient.getInstance();

  @Initializer(after = InitMilestone.JOB_LOADED)
  public void migrateResumeProperty() {
    Jenkins.get()
        .getItems(Folder.class)
        .stream()
        .flatMap(folder -> folder.getItems().stream())
        .filter(jenkinsClient::isCreatedByPipelineConfig)
        .forEach(this::setResumeBlocked);
  }

  /**
   * Disable the resume for this item
   *
   * @param item the item need to be disable the resume
   */
  private void setResumeBlocked(Item item) {
    if (item instanceof WorkflowJob) {
      WorkflowJob job = ((WorkflowJob) item);
      WorkflowJobProperty property = jenkinsClient.getWorkflowJobProperty(job);
      if (property == null || property.isConfiguredDefaultResume()) {
        return;
      }

      property.setConfiguredDefaultResume(true);
      job.setResumeBlocked(true);
    }

    if (item instanceof WorkflowMultiBranchProject) {
      WorkflowMultiBranchProject project = ((WorkflowMultiBranchProject) item);
      MultiBranchProperty property = jenkinsClient.getMultiBranchProperty(project);
      if (property == null || property.isConfiguredDefaultResume()) {
        return;
      }

      property.setConfiguredDefaultResume(true);
      project.getItems().forEach(job -> job.setResumeBlocked(true));
    }
  }
}
