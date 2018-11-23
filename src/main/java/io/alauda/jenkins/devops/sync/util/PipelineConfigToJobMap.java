package io.alauda.jenkins.devops.sync.util;

import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class PipelineConfigToJobMap {

    private static Map<String, WorkflowJob> pipelineConfigToJobMap;

    private PipelineConfigToJobMap() {
    }

    public static synchronized void initializePipelineConfigToJobMap() {
        List<WorkflowJob> jobs = Jenkins.getInstance().getAllItems(WorkflowJob.class);
        if (pipelineConfigToJobMap == null) {
            pipelineConfigToJobMap = new ConcurrentHashMap<>(jobs.size());
        }

        for (WorkflowJob job : jobs) {
            WorkflowJobProperty workflowJobProperty = job.getProperty(WorkflowJobProperty.class);
            if (workflowJobProperty == null) {
                continue;
            }

            String pcUid = workflowJobProperty.getUid();
            if (isNotBlank(pcUid)) {
                pipelineConfigToJobMap.put(pcUid, job);
            }
        }
    }

    public static synchronized WorkflowJob getJobFromPipelineConfig(PipelineConfig pipelineConfig) {
        ObjectMeta meta = pipelineConfig.getMetadata();
        if (meta == null) {
            return null;
        }

        return getJobFromPipelineConfigUid(meta.getUid());
    }

    public static synchronized WorkflowJob getJobFromPipelineConfigUid(String uid) {
        if (isBlank(uid)) {
            return null;
        }

        return pipelineConfigToJobMap.get(uid);
    }

    public static synchronized void putJobWithPipelineConfig(WorkflowJob job, PipelineConfig pipelineConfig) {
        if (pipelineConfig == null) {
            throw new IllegalArgumentException("PipelineConfig cannot be null");
        }
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }
        ObjectMeta meta = pipelineConfig.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException("PipelineConfig must contain valid metadata");
        }

        putJobWithPipelineConfigUid(job, meta.getUid());
    }

    static synchronized void putJobWithPipelineConfigUid(WorkflowJob job, String uid) {
        if (isBlank(uid)) {
            throw new IllegalArgumentException("PipelineConfig uid must not be blank");
        }
        pipelineConfigToJobMap.put(uid, job);
    }

    public static synchronized void removeJobWithPipelineConfig(PipelineConfig pipelineConfig) {
        if (pipelineConfig == null) {
            throw new IllegalArgumentException("PipelineConfig cannot be null");
        }
        ObjectMeta meta = pipelineConfig.getMetadata();
        if (meta == null) {
            throw new IllegalArgumentException("PipelineConfig must contain valid metadata");
        }
        removeJobWithPipelineConfigUid(meta.getUid());
    }

    static synchronized void removeJobWithPipelineConfigUid(String uid) {
        if (isBlank(uid)) {
            throw new IllegalArgumentException("PipelineConfig uid must not be blank");
        }
        pipelineConfigToJobMap.remove(uid);
    }

}
