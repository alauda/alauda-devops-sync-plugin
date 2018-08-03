package io.alauda.jenkins.devops.sync.constants;

public final class PipelineRunPolicy {
    private PipelineRunPolicy() {}

    public static final String PARALLEL = "Parallel";
    public static final String SERIAL = "Serial";

    // NOT IMPLEMENTED YET
    public static final String SERIAL_LATEST_ONLY = "SerialLatestOnly";
}
