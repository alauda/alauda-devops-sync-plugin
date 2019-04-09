package io.alauda.jenkins.devops.sync.input;

import hudson.model.ParameterDefinition;
import io.alauda.jenkins.devops.sync.model.InputRequest;
import io.alauda.jenkins.devops.sync.model.InputRequestParam;
import io.alauda.jenkins.devops.sync.model.ParamValueParser;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Logger;

public final class InputStepConvert {
    private static final Logger LOGGER = Logger.getLogger(InputStepConvert.class.getName());

    private InputStepConvert(){}

    public static InputRequest convert(InputStep input, Iterator<ParamValueParser> parserIt) {
        InputRequest inputRequest = new InputRequest();
        inputRequest.setId(input.getId());
        inputRequest.setMessage(input.getMessage());
        inputRequest.setSubmitter(input.getSubmitter());

        List<ParameterDefinition> params = input.getParameters();
        if(params != null) {
            final List<InputRequestParam> inputRequestParams = new ArrayList<>();
            inputRequest.setParams(inputRequestParams);

            params.forEach(param -> {
                InputRequestParam requestParam = toInputRequestParam(parserIt, param);

                if(requestParam != null) {
                    inputRequestParams.add(requestParam);
                } else {
                    LOGGER.warning("Not support for " + param);
                }
            });
        }

        return inputRequest;
    }

    public static InputRequestParam toInputRequestParam(Iterator<ParamValueParser> parserIt, ParameterDefinition param) {
        String paramType = param.getType();
        InputRequestParam requestParam = null;

        while(parserIt.hasNext()) {
            ParamValueParser parser = parserIt.next();
            if(parser.accept(paramType)) {
                requestParam = parser.toInputRequestParam(param);
                break;
            }
        }

        return requestParam;
    }
}
