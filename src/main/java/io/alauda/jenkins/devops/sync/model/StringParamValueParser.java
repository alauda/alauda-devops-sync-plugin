package io.alauda.jenkins.devops.sync.model;

import hudson.Extension;
import hudson.model.ParameterDefinition;
import hudson.model.StringParameterDefinition;

@Extension
public class StringParamValueParser implements ParamValueParser {
    @Override
    public boolean accept(String type) {
        return StringParameterDefinition.class.getSimpleName().equals(type);
    }

    @Override
    public InputRequestParam toInputRequestParam(ParameterDefinition param) {
        InputRequestParam inputRequestParam = new InputRequestParam();

        inputRequestParam.setName(param.getName());
        inputRequestParam.setDescription(param.getDescription());
        inputRequestParam.setType(StringParameterDefinition.class.getSimpleName());

        if(param instanceof StringParameterDefinition) {
            StringParameterDefinition def = (StringParameterDefinition) param;
            inputRequestParam.setDefaultValue(def.getDefaultValue());
        }

        return inputRequestParam;
    }
}
