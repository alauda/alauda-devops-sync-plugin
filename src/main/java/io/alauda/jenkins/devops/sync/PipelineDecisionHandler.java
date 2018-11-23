/**
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.*;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.listener.PipelineSyncRunListener;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.PipelineGenerator;
import io.alauda.jenkins.devops.sync.util.PipelineToActionMapper;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.client.KubernetesClientException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * In this handler, we just handle the case of triggered by user. We will pass of other cases.
 *
 * @author suren
 */
@Extension
public class PipelineDecisionHandler extends Queue.QueueDecisionHandler {

    private static final Logger LOGGER = Logger.getLogger(PipelineDecisionHandler.class.getName());

    @Override
    public boolean shouldSchedule(Queue.Task p, List<Action> actions) {
        if (p instanceof WorkflowJob && triggerFromJenkins(actions)) {
            // in case of triggered by users
            WorkflowJob workflowJob = (WorkflowJob) p;
            String taskName = p.getName();
            WorkflowJobProperty workflowJobProperty = workflowJob.getProperty(WorkflowJobProperty.class);
            if (workflowJobProperty == null || !hasValidProperty(workflowJob)) {
                return true;
            }

            final String namespace = workflowJobProperty.getNamespace();
            final String name = workflowJobProperty.getName();
            final String jobURL = getJobUrl(workflowJob, namespace);

            LOGGER.info(() -> "Got this namespace " + namespace + " from this workflowJobProperty: " + name);
            // TODO: Add trigger API for pipelineconfig (like above)

            PipelineConfig config = null;
            try {
                config = AlaudaUtils.getAuthenticatedAlaudaClient()
                        .pipelineConfigs().inNamespace(namespace)
                        .withName(name).get();
            } catch (KubernetesClientException e) {
                LOGGER.warning(() -> e.getMessage() + "; cause: " + e.getCause().getMessage());
            }

            if (config == null) {
                return false;
            } else if (config.getMetadata() == null) {
                LOGGER.warning("PipelineConfig metadata is null");
                return false;
            }

            Pipeline pipeline;
            try {
                // create k8s resource(Pipeline)
                pipeline = PipelineGenerator.buildPipeline(config, jobURL, actions);
            } catch (KubernetesClientException e) {
                LOGGER.warning(config.getMetadata().getName() + " got error : " + e.getMessage());

                if(e.getCode() == 409) {
                    PipelineUtils.pipelinesCheck(config);
                }

                return false;
            }

            ParametersAction params = dumpParams(actions);
            if (params != null) {
                LOGGER.fine(() -> "ParametersAction: " + params.toString());
                PipelineToActionMapper.addParameterAction(pipeline.getMetadata().getName(), params);
            } else {
                LOGGER.log(Level.FINE, "The param is null in task : {0}", taskName);
            }

            CauseAction cause = dumpCause(actions);
            if (cause != null) {
                LOGGER.fine(() -> "get CauseAction: " + cause.getDisplayName());
                for (Cause c : cause.getCauses()) {
                    LOGGER.fine(() -> "Cause: " + c.getShortDescription());
                }

                PipelineToActionMapper.addCauseAction(pipeline.getMetadata().getName(), cause);
            } else {
                LOGGER.fine(() -> "Get null CauseAction in task : " + taskName);
            }

            // we already create k8s resource, and waiting next round
            return false;
        }

        return true;
    }

    private String getJobUrl(WorkflowJob workflowJob, String namespace) {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return null;
        }

        String jenkinsUrl = AlaudaUtils.getJenkinsURL(client, namespace);
        return PipelineSyncRunListener.joinPaths(jenkinsUrl, workflowJob.getUrl());
    }

    private boolean hasValidProperty(WorkflowJob workflowJob) {
        WorkflowJobProperty property = workflowJob.getProperty(WorkflowJobProperty.class);
        if (property == null) {
            return false;
        }

        return (StringUtils.isNotBlank(property.getNamespace()) && StringUtils.isNotBlank(property.getName()));
    }

    private static boolean triggerFromJenkins(@Nonnull List<Action> actions) {
        return !triggerFromPlatform(actions);
    }

    private static boolean triggerFromPlatform(@Nonnull List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                CauseAction causeAction = (CauseAction) action;
                for (Cause cause : causeAction.getCauses()) {
                    if (cause instanceof JenkinsPipelineCause) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Just for find the first CauseAction and print debug info
     *
     * @param actions action list
     * @return causeAction
     */
    private CauseAction dumpCause(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof CauseAction) {
                CauseAction causeAction = (CauseAction) action;
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (Cause cause : causeAction.getCauses()) {
                        LOGGER.fine(() -> "cause: " + cause.getShortDescription());
                    }
                }

                return causeAction;
            }
        }
        return null;
    }

    private static ParametersAction dumpParams(List<Action> actions) {
        for (Action action : actions) {
            if (action instanceof ParametersAction) {
                ParametersAction paramAction = (ParametersAction) action;
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (ParameterValue param : paramAction.getAllParameters()) {
                        LOGGER.fine(() -> "param name " + param.getName() + " param value " + param.getValue());
                    }
                }
                return paramAction;
            }
        }
        return null;
    }

}
