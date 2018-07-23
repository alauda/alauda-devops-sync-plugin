package io.alauda.jenkins.devops.sync.constants;

public interface PipelinePhases {
  // TODO it'd be nice to code generate these from the go source code
  // in kubernetes-model

  // NEW is automatically assigned to a newly created build.
  String NEW = "New";

  // PENDING indicates that a pod name has been assigned and a build is
  // about to start running.
  String PENDING = "Pending";

  // QUEUED indicates that a pod name has been assigned and a build is
  // about to start running.
  String QUEUED = "Queued";

  // RUNNING indicates that a pod has been created and a build is running.
  String RUNNING = "Running";

  // COMPLETE indicates that a build has been successful.
  String COMPLETE = "Complete";

  // FAILED indicates that a build has executed and failed.
  String FAILED = "Failed";

  // ERROR indicates that an error prevented the build from executing.
  String ERROR = "Error";

  // CANCELLED indicates that a running/pending build was stopped from
  // executing.
  String CANCELLED = "Cancelled";

  String FINISHED = "Finished";
  String SKIPPED = "Skipped";
  String NOT_BUILT = "NotBuilt";
  String PAUSED = "Paused";
}
