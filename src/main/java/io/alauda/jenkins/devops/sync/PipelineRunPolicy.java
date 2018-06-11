package io.alauda.jenkins.devops.sync;

public interface PipelineRunPolicy {

    /** NOT IMPLEMENTED YET */
    String PARALLEL = "Parallel";
    String SERIAL = "Serial";
    /** NOT IMPLEMENTED YET */
    String SERIAL_LATEST_ONLY = "SerialLatestOnly";
}
