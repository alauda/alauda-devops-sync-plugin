package io.alauda.jenkins.devops.sync.event;

import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.jenkins.devops.sync.event.Events.EventType;
import io.kubernetes.client.models.V1ObjectReference;
import javax.annotation.Nonnull;

public class PipelineEvents {

  private static final String PIPELINE_KIND = "Pipeline";

  public static final String REASON_JENKINS_BUILD_TRIGGERED = "BuildTriggered";
  public static final String REASON_FAILED_TRIGGER_JENKINS_BUILD = "FailedTriggerBuild";
  public static final String REASON_JENKINS_BUILD_CANCELLED = "BuildCancelled";
  public static final String REASON_FAILED_CANCEL_JENKINS_BUILD = "FailedCancelBuild";
  public static final String REASON_JENKINS_BUILD_DELETED = "BuildDeleted";
  public static final String REASON_FAILED_DELETE_JENKINS_BUILD = "FailedDeleteBuild";
  public static final String REASON_FAILED_UPDATE_JENKINS_BUILD = "FailedUpdateBuild";
  public static final String REASON_JENKINS_BUILD_UPDATED = "BuildUpdated";

  public static Event newBuildUpdatedEvent(@Nonnull V1alpha1Pipeline pipeline, @Nonnull String message) {
    return newNormalPipelineEvent(pipeline, REASON_JENKINS_BUILD_UPDATED, message);
  }

  public static Event newFailedUpdateBuildEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String message) {
    return newWarningPipelineEvent(pipeline, REASON_FAILED_UPDATE_JENKINS_BUILD, message);
  }

  public static Event newBuildDeletedEvent(
      @Nonnull String namespace, String name, @Nonnull String message) {

    return Events.newEvent(
        new V1ObjectReference().namespace(namespace).name(name).kind(PIPELINE_KIND),
        EventType.EventTypeNormal,
        REASON_JENKINS_BUILD_DELETED,
        message);
  }

  public static Event newFailedDeleteBuildEvent(
      @Nonnull String namespace, String name, @Nonnull String message) {
    return Events.newEvent(
        new V1ObjectReference().namespace(namespace).name(name).kind(PIPELINE_KIND),
        EventType.EventTypeWarning,
        REASON_FAILED_DELETE_JENKINS_BUILD,
        message);
  }

  public static Event newFailedCancelBuildEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String message) {
    return newWarningPipelineEvent(pipeline, REASON_FAILED_CANCEL_JENKINS_BUILD, message);
  }

  public static Event newBuildCancelledEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String message) {
    return newNormalPipelineEvent(pipeline, REASON_JENKINS_BUILD_CANCELLED, message);
  }

  public static Event newFailedTriggerBuildEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String message) {
    return newWarningPipelineEvent(pipeline, REASON_FAILED_TRIGGER_JENKINS_BUILD, message);
  }

  public static Event newBuildTriggeredEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String message) {
    return newNormalPipelineEvent(pipeline, REASON_JENKINS_BUILD_TRIGGERED, message);
  }

  public static Event newNormalPipelineEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String reason, @Nonnull String message) {
    return newPipelineEvent(pipeline, EventType.EventTypeNormal, reason, message);
  }

  public static Event newWarningPipelineEvent(
      @Nonnull V1alpha1Pipeline pipeline, @Nonnull String reason, @Nonnull String message) {
    return newPipelineEvent(pipeline, EventType.EventTypeWarning, reason, message);
  }

  public static Event newPipelineEvent(
      @Nonnull V1alpha1Pipeline pipeline,
      @Nonnull EventType eventType,
      @Nonnull String reason,
      @Nonnull String message) {
    V1ObjectReference reference =
        Events.convertToObjectReference(
            pipeline.getApiVersion(), pipeline.getKind(), pipeline.getMetadata());

    return Events.newEvent(reference, eventType, reason, message);
  }
}
