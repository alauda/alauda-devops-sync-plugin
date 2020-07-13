package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.displayurlapi.DisplayURLProvider;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.parboiled.common.StringUtils;

@Extension
public class AlaudaDisplayURLProvider extends DisplayURLProvider {

  private static final String ALAUDA_DEVOPS_PLATFORM_ADDRESS_ENV = "ALAUDA_DEVOPS_PLATFORM_ADDRESS";
  private static final String ALAUDA_DEVOPS_PLATFORM_ADDRESS;

  private JenkinsClient jenkinsClient = JenkinsClient.getInstance();

  static {
    String alaudaDevOpsPlatformAddress = System.getenv(ALAUDA_DEVOPS_PLATFORM_ADDRESS_ENV);
    if (StringUtils.isNotEmpty(alaudaDevOpsPlatformAddress)) {
      if (!alaudaDevOpsPlatformAddress.endsWith("/")) {
        alaudaDevOpsPlatformAddress = alaudaDevOpsPlatformAddress + "/";
      }
    }
    ALAUDA_DEVOPS_PLATFORM_ADDRESS = alaudaDevOpsPlatformAddress;
  }

  @Nonnull
  @Override
  public String getDisplayName() {
    return "DevOps Platform";
  }

  @Nonnull
  @Override
  public String getName() {
    return "devops-platform";
  }

  @Nonnull
  @Override
  public String getRoot() {
    if (StringUtils.isEmpty(ALAUDA_DEVOPS_PLATFORM_ADDRESS)) {
      return super.getRoot();
    }
    return ALAUDA_DEVOPS_PLATFORM_ADDRESS;
  }

  @Nonnull
  @Override
  public String getRunURL(Run<?, ?> run) {
    if (StringUtils.isEmpty(ALAUDA_DEVOPS_PLATFORM_ADDRESS)) {
      return DisplayURLProvider.getDefault().getRunURL(run);
    }

    JenkinsPipelineCause alaudaCause = PipelineUtils.findAlaudaCause(run);
    if (alaudaCause == null) {
      return DisplayURLProvider.getDefault().getRunURL(run);
    }

    WorkflowJob job = ((WorkflowJob) run.getParent());

    return String.format("%s%s", getJobURL(job), alaudaCause.getName());
  }

  @Nonnull
  @Override
  public String getChangesURL(Run<?, ?> run) {
    // we don't support to display changes, so use the default implementation
    return DisplayURLProvider.getDefault().getChangesURL(run);
  }

  @Nonnull
  @Override
  public String getJobURL(Job<?, ?> job) {
    if (StringUtils.isEmpty(ALAUDA_DEVOPS_PLATFORM_ADDRESS)) {
      return DisplayURLProvider.getDefault().getJobURL(job);
    }

    AlaudaJobProperty alaudaJobProperty = getAlaudaJobProperty(job);
    if (alaudaJobProperty == null) {
      return DisplayURLProvider.getDefault().getJobURL(job);
    }

    return String.format(
        "%sworkspace/%s/pipelines/all/%s/",
        getRoot(), alaudaJobProperty.getNamespace(), alaudaJobProperty.getName());
  }

  private AlaudaJobProperty getAlaudaJobProperty(Job<?, ?> job) {
    if (job.getParent() instanceof WorkflowMultiBranchProject) {
      return jenkinsClient.getMultiBranchProperty(((WorkflowMultiBranchProject) job.getParent()));
    }

    if (job instanceof WorkflowJob) {
      return jenkinsClient.getWorkflowJobProperty(((WorkflowJob) job));
    }

    return null;
  }
}
