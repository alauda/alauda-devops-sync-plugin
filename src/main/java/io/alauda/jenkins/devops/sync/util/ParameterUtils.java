package io.alauda.jenkins.devops.sync.util;

import hudson.model.BooleanParameterValue;
import hudson.model.ParameterValue;
import hudson.model.StringParameterValue;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameterBuilder;

/**
 * Convert between PipelineParameter and ParameterValue
 *
 * @author suren
 */
public abstract class ParameterUtils {
  private ParameterUtils() {}

  public static V1alpha1PipelineParameter to(ParameterValue parameterValue) {
    if (parameterValue == null) {
      return null;
    }

    String type;
    String value = null;
    if (parameterValue instanceof StringParameterValue) {
      type = "StringParameterDefinition";
      value = String.valueOf(parameterValue.getValue());
    } else if (parameterValue instanceof BooleanParameterValue) {
      type = "BooleanParameterDefinition";
      value = ((BooleanParameterValue) parameterValue).getValue().toString();
    } else {
      return null;
    }

    return new V1alpha1PipelineParameterBuilder()
        .withType(type)
        .withName(parameterValue.getName())
        .withDescription(parameterValue.getDescription())
        .withValue(value)
        .build();
  }
}
