package io.alauda.jenkins.devops.sync.constants;

public final class PipelinePhases {
  private PipelinePhases() {}

  // TODO it'd be nice to code generate these from the go source code
  // in kubernetes-model

  // NEW is automatically assigned to a newly created build.
  public static final String NEW = "New";

  // PENDING indicates that a pod name has been assigned and a build is
  // about to start running.
  public static final String PENDING = "Pending";

  // QUEUED indicates that a pod name has been assigned and a build is
  // about to start running.
  public static final String QUEUED = "Queued";

  // RUNNING indicates that a pod has been created and a build is running.
  public static final String RUNNING = "Running";

  // COMPLETE indicates that a build has been successful.
  public static final String COMPLETE = "Complete";

  // FAILED indicates that a build has executed and failed.
  public static final String FAILED = "Failed";

  public static final String CANCELLING = "Cancelling";

  // ERROR indicates that an error prevented the build from executing.
  public static final String ERROR = "Error";

  // CANCELLED indicates that a running/pending build was stopped from
  // executing.
  public static final String CANCELLED = "Cancelled";

  public static final String FINISHED = "Finished";
  public static final String SKIPPED = "Skipped";
  public static final String NOT_BUILT = "NotBuilt";
  public static final String PAUSED = "Paused";
}
