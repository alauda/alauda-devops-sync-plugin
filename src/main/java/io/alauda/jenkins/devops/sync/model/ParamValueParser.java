package io.alauda.jenkins.devops.sync.model;

import hudson.ExtensionPoint;
import hudson.model.ParameterDefinition;

public interface ParamValueParser extends ExtensionPoint {
    boolean accept(String type);

    InputRequestParam toInputRequestParam(ParameterDefinition param);
}
