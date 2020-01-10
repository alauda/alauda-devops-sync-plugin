package io.alauda.jenkins.devops.sync.constants;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;

public class AnnotationProvider {

  private static AnnotationProvider annotationProvider;

  private String baseDomain = null;

  private AnnotationProvider(String baseDomain) {
    this.baseDomain = baseDomain;
  }

  @Nonnull
  public static AnnotationProvider getInstance() {
    if (annotationProvider == null || StringUtils.isEmpty(annotationProvider.getBaseDomain())) {
      throw new IllegalStateException(
          "AnnotationProvider hasn't initialized by ResourceControllerManager");
    }

    return annotationProvider;
  }

  @Nonnull
  public synchronized static AnnotationProvider initialize(@Nonnull String baseDomain) {
    if (StringUtils.isEmpty(baseDomain)) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid baseDomain value: %s, unable to initialize AnnotationProvider", baseDomain));
    }

    if (annotationProvider == null) {
      annotationProvider = new AnnotationProvider(baseDomain);
    } else {
      annotationProvider.setBaseDomain(baseDomain);
    }

    return annotationProvider;
  }

  @Nullable
  private String getBaseDomain() {
    return baseDomain;
  }

  private void setBaseDomain(@Nonnull String baseDomain) {
    this.baseDomain = baseDomain;
  }

  public String annotationJobPath() {
    return wrapper("job-path");
  }

  public String annotationMultiBranchOpenBranch() {
    return wrapper("jenkins.branch");
  }

  public String annotationMultiBranchStaleBranch() {
    return wrapper("jenkins.stale.branch");
  }

  public String annotationMultiBranchPR() {
    return wrapper("jenkins.pr");
  }

  public String annotationMultiBranchStalePR() {
    return wrapper("jenkins.stale.pr");
  }

  public String annotationMultiBranchCategory() {
    return wrapper("multiBranchCategory");
  }

  public String annotationMultiBranchName() {
    return wrapper("multiBranchName");
  }

  public String annotationMultiBranchPRDetail() {
    return wrapper("jenkins.pr.detail");
  }

  public String annotationPipelineContext() {
    return wrapper("pipelinecontext.");
  }

  public String annotationPipelineNumber() {
    return wrapper("pipeline.number");
  }

  public String annotationCommit() {
    return wrapper("commit");
  }

  public String annotationJenkinsBuildURI() {
    return wrapper("jenkins-build-uri");
  }

  public String annotationJenkinsLogURL() {
    return wrapper("jenkins-log-url");
  }

  public String annotationJenkinsConsoleLogURL() {
    return wrapper("jenkins-console-log-url");
  }

  public String annotationJenkinsBlueOceanLogURL() {
    return wrapper("jenkins-blueocean-log-url");
  }

  public String annotationJenkinsStagesLog() {
    return wrapper("jenkins-stages-log");
  }

  public String annotationJenkinsStages() {
    return wrapper("jenkins-stages");
  }

  public String annotationJenkinsStepsLog() {
    return wrapper("jenkins-steps-log");
  }

  public String annotationJenkinsSteps() {
    return wrapper("jenkins-steps");
  }

  public String annotationJenkinsViewLog() {
    return wrapper("jenkins-view-log");
  }

  public String annotationJenkinsProgressiveLog() {
    return wrapper("jenkins-progressive-log");
  }

  public String annotationCausesDetails() {
    return wrapper("causes-details");
  }

  public String annotationJenkinsPendingInputActionJSON() {
    return wrapper("jenkins-pending-input-actions-json");
  }

  public String annotationJenkinsStatusJSON() {
    return wrapper("jenkins-status-json");
  }

  public String annotationJenkinsStagesJSON() {
    return wrapper("jenkins-stages-json");
  }

  public String annotationJenkinsNamespace() {
    return wrapper("jenkins-namespace");
  }

  public String annotationMultiBranchScanLog() {
    return wrapper("multi-branch-scan-log");
  }

  public String annotationJenkinsIdentity() {
    return wrapper("jenkins-instance-identity");
  }

  public String annotationJenkinsBadges() {
    return wrapper("jenkins-badges");
  }

  /**
   * wrapper the annotation with the base domain.
   *
   * <p>For example, if we pass "jenkins-url" we will get "base-domain/jenkins-url"
   *
   * @return annotation with the base domain
   */
  public String get(String original) {
    return wrapper(original);
  }

  private String wrapper(String annotation) {
    return baseDomain + "/" + annotation;
  }
}
