package io.alauda.jenkins.devops.sync;

import hudson.model.TopLevelItem;
import io.alauda.kubernetes.api.model.PipelineConfig;

public interface PipelineConfigConvert<T extends TopLevelItem> {
    T convert(PipelineConfig pipelineConfig);
}
