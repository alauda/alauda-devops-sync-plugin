package io.alauda.jenkins.devops.sync.event;

import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.event.Events.EventType;
import io.kubernetes.client.models.V1ObjectReference;
import javax.annotation.Nonnull;

public class PipelineConfigEvents {

  private static final String PIPELINE_CONFIG_KIND = "PipelineConfig";
  private static final String REASON_JENKINS_JOB_DELETED = "JobDeleted";
  private static final String REASON_JENKINS_JOB_FAILED_DELETE = "FailedDeletedJob";
  private static final String REASON_JENKINS_JOB_CREATED = "JobCreated";
  private static final String REASON_JENKINS_JOB_FAILED_CREATE = "FailedCreateJob";
  private static final String REASON_JENKINS_JOB_UPDATED = "JobUpdated";
  private static final String REASON_JENKINS_JOB_FAILED_UPDATE = "FailedUpdateJob";

  public static Event newFailedUpdateJobEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig, @Nonnull String message) {
    return newWarningEvent(pipelineConfig, REASON_JENKINS_JOB_FAILED_UPDATE, message);
  }

  public static Event newJobUpdatedEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig, @Nonnull String message) {
    return newNormalEvent(pipelineConfig, REASON_JENKINS_JOB_UPDATED, message);
  }

  public static Event newFailedCreateJobEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig, @Nonnull String message) {
    return newWarningEvent(pipelineConfig, REASON_JENKINS_JOB_FAILED_CREATE, message);
  }

  public static Event newJobCreatedEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig, @Nonnull String message) {
    return newNormalEvent(pipelineConfig, REASON_JENKINS_JOB_CREATED, message);
  }

  public static Event newJobDeletedEvent(
      @Nonnull String namespace, @Nonnull String name, @Nonnull String message) {
    return Events.newEvent(
        new V1ObjectReference().namespace(namespace).name(name).kind(PIPELINE_CONFIG_KIND),
        EventType.EventTypeNormal,
        REASON_JENKINS_JOB_DELETED,
        message);
  }

  public static Event newFailedDeleteJobEvent(
      @Nonnull String namespace, @Nonnull String name, @Nonnull String message) {
    return Events.newEvent(
        new V1ObjectReference().namespace(namespace).name(name).kind(PIPELINE_CONFIG_KIND),
        EventType.EventTypeWarning,
        REASON_JENKINS_JOB_FAILED_DELETE,
        message);
  }

  public static Event newNormalEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig,
      @Nonnull String reason,
      @Nonnull String message) {
    return newEvent(pipelineConfig, EventType.EventTypeNormal, reason, message);
  }

  public static Event newWarningEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig,
      @Nonnull String reason,
      @Nonnull String message) {
    return newEvent(pipelineConfig, EventType.EventTypeWarning, reason, message);
  }

  public static Event newEvent(
      @Nonnull V1alpha1PipelineConfig pipelineConfig,
      @Nonnull EventType eventType,
      @Nonnull String reason,
      @Nonnull String message) {
    V1ObjectReference reference =
        Events.convertToObjectReference(
            pipelineConfig.getApiVersion(), pipelineConfig.getKind(), pipelineConfig.getMetadata());

    return Events.newEvent(reference, eventType, reason, message);
  }
}
