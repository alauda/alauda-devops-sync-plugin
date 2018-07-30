package io.alauda.jenkins.devops.sync.constants;

public interface PipelineRunPolicy {

  String PARALLEL = "Parallel";
  String SERIAL = "Serial";

  // NOT IMPLEMENTED YET
  String SERIAL_LATEST_ONLY = "SerialLatestOnly";
}
