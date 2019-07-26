package io.alauda.jenkins.devops.sync.util;

import hudson.model.TopLevelItem;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigProjectProperty;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.kubernetes.client.models.V1ObjectMeta;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class PipelineConfigToJobMap {
    private static final Logger LOGGER = Logger.getLogger(PipelineConfigToJobMap.class.getName());

    private static Map<String, TopLevelItem> pipelineConfigToJobMap;

    private PipelineConfigToJobMap() {
    }



    @Deprecated
    public static synchronized WorkflowJob getJobFromPipelineConfigUid(String uid) {
        if (isBlank(uid)) {
            return null;
        }

        TopLevelItem item = pipelineConfigToJobMap.get(uid);
        if(item instanceof WorkflowJob) {
            return (WorkflowJob) item;
        }

        if (item == null) {
            LOGGER.log(Level.WARNING, String.format("Unable to find job by uid %s in pipelineconfig Map", uid));
        }

        return null;
    }


}
