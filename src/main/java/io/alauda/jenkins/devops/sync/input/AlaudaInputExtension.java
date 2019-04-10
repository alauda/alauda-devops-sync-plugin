package io.alauda.jenkins.devops.sync.input;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.Run;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.model.InputRequest;
import io.alauda.jenkins.devops.sync.model.ParamValueParser;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.Pipeline;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.support.steps.input.InputExtension;
import org.jenkinsci.plugins.workflow.support.steps.input.InputStep;

import java.util.Iterator;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_INPUT_REQUESTS;

@Extension
public class AlaudaInputExtension implements InputExtension {
    private static final Logger LOGGER = Logger.getLogger(AlaudaInputExtension.class.getName());

    @Override
    public void notifyInput(InputStep input, Run run, String userID, NotifyEvent event) {
        switch (event) {
            case START:
                addInputRequest(input, run);
                break;
            case ABORT:
            case PROCEED:
                updateInputRequest(input, run, userID, event);
                break;
        }
    }

    private void addInputRequest(InputStep input, Run run) {
        final String message = input.getMessage();
        LOGGER.info(String.format("Receive a input request, message is: %s", message));

        Pipeline targetPipeline = findPipeline(run);
        if(targetPipeline == null) {
            // TODO need to consider how to deal with the bad situation with k8s client
            return;
        }

        String namespace = targetPipeline.getMetadata().getNamespace();
        String name = targetPipeline.getMetadata().getName();

        JSONArray jsonArray = getAndAddRequestJson(targetPipeline, input, run);
        updatePipeline(namespace, name, jsonArray.toString());
    }

    private void updateInputRequest(InputStep input, Run run, String userID, NotifyEvent event) {
        Pipeline targetPipeline = findPipeline(run);
        if(targetPipeline == null) {
            // TODO need to consider how to deal with the bad situation with k8s client
            return;
        }

        String namespace = targetPipeline.getMetadata().getNamespace();
        String name = targetPipeline.getMetadata().getName();

        JSONArray jsonArray = getRequestJson(targetPipeline);
        if(jsonArray == null) {
            LOGGER.info(String.format("Cannot update input request: %s", input.getMessage()));
            return;
        }

        boolean found = false;
        for(Object request : jsonArray) {
            if(!(request instanceof JSONObject)) {
                continue;
            }

            JSONObject jsonObj = (JSONObject) request;

            if(input.getId().equals(jsonObj.getString("id"))) {
                jsonObj.put("status", event.name());
                found = true;
                break;
            }
        }

        if(found) {
            updatePipeline(namespace, name, jsonArray.toString());
        } else {
            LOGGER.warning(String.format("Cannot find input request by id: %s", input.getId()));
        }
    }

    private void updatePipeline(String namespace, String name, String requestJson) {
        // add annotations to pipeline resource
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        client.pipelines().inNamespace(namespace).withName(name).edit()
                .editMetadata().addToAnnotations(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_INPUT_REQUESTS, requestJson)
                .endMetadata().done();
    }

    private Pipeline findPipeline(Run run) {
        Job job = run.getParent();
        if(job instanceof WorkflowJob && run instanceof WorkflowRun) {
            WorkflowJob wfJob = (WorkflowJob) job;
            WorkflowRun wfRun = (WorkflowRun) run;

            JenkinsPipelineCause cause = wfRun.getCause(JenkinsPipelineCause.class);
            if(cause == null) {
                LOGGER.info("Current pipeline was not create by Alauda platform, because no JenkinsPipelineCause found.");
                return null;
            }

            String namespace = cause.getNamespace();
            String name = cause.getName();

            // add annotations to pipeline resource
            AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
            if(client == null) {
                LOGGER.warning(String.format("Cannot get alauda client when process input request in workflow: %s, build id: %s.",
                        wfJob.getFullName(), wfRun.getId()));
                return null;
            }

            return client.pipelines().inNamespace(namespace).withName(name).get();
        }

        return null;
    }

    private JSONArray getRequestJson(Pipeline targetPipeline) {
        String inputRequestJsonText = null;
        Map<String, String> annotations = targetPipeline.getMetadata().getAnnotations();
        if(annotations != null && annotations.containsKey(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_INPUT_REQUESTS)) {
            inputRequestJsonText = annotations.get(ALAUDA_DEVOPS_ANNOTATIONS_JENKINS_INPUT_REQUESTS);
        }

        JSONArray jsonArray = new JSONArray();
        try {
            if(inputRequestJsonText != null) {
                jsonArray = JSONArray.fromObject(inputRequestJsonText);
            }
        } catch (JSONException e) {
            LOGGER.warning(String.format("Cannot parse json array text: %s", inputRequestJsonText));
        }

        return jsonArray;
    }

    private JSONArray getAndAddRequestJson(Pipeline targetPipeline, InputStep input, Run run) {
        JSONArray jsonArray = getRequestJson(targetPipeline);
        String buildID = run.getId();
        String baseURI = run.getUrl();

        Iterator<ParamValueParser> paramValueParserIt = Jenkins.getInstance()
                .getExtensionList(ParamValueParser.class).iterator();

        InputRequest inputRequest = InputStepConvert.convert(input, paramValueParserIt);
        inputRequest.setBuildID(buildID);
        inputRequest.setBaseURI(baseURI);
        jsonArray.add(inputRequest);

        return jsonArray;
    }
}
