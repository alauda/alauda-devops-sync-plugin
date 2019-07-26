package io.alauda.jenkins.devops.sync.mapper.converter;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import hudson.model.TopLevelItem;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import jenkins.model.Jenkins;

import java.io.IOException;

public interface JobConverter<T extends TopLevelItem> extends ExtensionPoint {

    boolean accept(V1alpha1PipelineConfig pipelineConfig);

    T convert(V1alpha1PipelineConfig pipelineConfig) throws PipelineConfigConvertException, IOException;

    static ExtensionList<JobConverter> all() {
        return Jenkins.getInstance().getExtensionList(JobConverter.class);
    }
}
