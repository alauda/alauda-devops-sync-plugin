package io.alauda.jenkins.devops.sync.util;

import hudson.model.BooleanParameterValue;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import io.alauda.kubernetes.api.model.PipelineParameter;
import io.alauda.kubernetes.api.model.PipelineParameterBuilder;

/**
 * Convert between PipelineParameter and ParameterValue
 * @author suren
 */
public abstract class ParameterUtils {
    public static PipelineParameter to(ParameterValue parameterValue) {
        if(parameterValue == null) {
            return null;
        }

        String type;
        String value = null;
        if(parameterValue instanceof StringParameterValue) {
            type = "string";
            value = String.valueOf(parameterValue.getValue());
        } else if(parameterValue instanceof BooleanParameterValue) {
            type = "boolean";
            value = ((BooleanParameterValue) parameterValue).getValue().toString();
        } else {
            return null;
        }

        return new PipelineParameterBuilder()
                .withType(type)
                .withName(parameterValue.getName())
                .withDescription(parameterValue.getDescription())
                .withValue(value)
                .build();
    }
}
