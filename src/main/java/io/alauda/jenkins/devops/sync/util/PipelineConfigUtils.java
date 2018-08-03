package io.alauda.jenkins.devops.sync.util;

import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.kubernetes.api.model.PipelineConfig;

public abstract class PipelineConfigUtils {
    private PipelineConfigUtils(){}

    public static boolean isSerialPolicy(PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            throw new IllegalArgumentException("param can't be null");
        }

        return Constants.PIPELINE_RUN_POLICY_SERIAL.equals(pipelineConfig.getSpec().getRunPolicy());
    }

    public static boolean isParallel(PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            throw new IllegalArgumentException("param can't be null");
        }

        return Constants.PIPELINE_RUN_POLICY_PARALLEL.equals(pipelineConfig.getSpec().getRunPolicy());
    }
}
