package io.alauda.jenkins.devops.sync.util;

import hudson.model.TopLevelItem;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class PipelineConfigToJobMap {
    private static Map<String, TopLevelItem> pipelineConfigToJobMap;

    private PipelineConfigToJobMap() {
    }

    public static synchronized void initializePipelineConfigToJobMap() {
        Jenkins jenkins = Jenkins.getInstance();
        List<WorkflowJob> jobs = jenkins.getAllItems(WorkflowJob.class);
        if (pipelineConfigToJobMap == null) {
            pipelineConfigToJobMap = new ConcurrentHashMap<>(jobs.size());
        }

        jobs.stream().filter(job -> {
            WorkflowJobProperty property = job.getProperty(WorkflowJobProperty.class);

            return (property != null && isNotBlank(property.getUid()));
        }).forEach(job -> {
            String uid = job.getProperty(WorkflowJobProperty.class).getUid();
            pipelineConfigToJobMap.put(uid, job);
        });

        List<WorkflowMultiBranchProject> wfMultiList = jenkins.getAllItems(WorkflowMultiBranchProject.class);
        wfMultiList.stream().filter(job -> {
            MultiBranchProperty property = job.getProperties().get(MultiBranchProperty.class);

            return (property != null && isNotBlank(property.getUid()));
        }).forEach(job -> {
            String uid = job.getProperties().get(MultiBranchProperty.class).getUid();
            pipelineConfigToJobMap.put(uid, job);
        });
    }

    @Deprecated
    public static synchronized WorkflowJob getJobFromPipelineConfig(PipelineConfig pipelineConfig) {
        ObjectMeta meta = pipelineConfig.getMetadata();
        if (meta == null) {
            return null;
        }

        return getJobFromPipelineConfigUid(meta.getUid());
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

        return null;
    }

    private static synchronized WorkflowMultiBranchProject getMultiBranchById(String uid) {
        TopLevelItem item = pipelineConfigToJobMap.get(uid);
        if(item instanceof WorkflowMultiBranchProject) {
            return (WorkflowMultiBranchProject) item;
        }
        return null;
    }

    public static synchronized WorkflowMultiBranchProject getMultiBranchByPC(PipelineConfig pc){
        ObjectMeta meta = pc.getMetadata();
        if (meta == null) {
            return null;
        }
        return getMultiBranchById(meta.getUid());
    }

    public static TopLevelItem getItemById(String uid) {
        return pipelineConfigToJobMap.get(uid);
    }

    public static synchronized TopLevelItem getItemByPC(PipelineConfig pc) {
        ObjectMeta meta = pc.getMetadata();
        if (meta == null) {
            return null;
        }
        return getItemById(meta.getUid());
    }

    public static synchronized void putJobWithPipelineConfig(TopLevelItem job, PipelineConfig pipelineConfig) {
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

    private static synchronized void putJobWithPipelineConfigUid(TopLevelItem job, String uid) {
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

    private static synchronized void removeJobWithPipelineConfigUid(String uid) {
        if (isBlank(uid)) {
            throw new IllegalArgumentException("PipelineConfig uid must not be blank");
        }
        pipelineConfigToJobMap.remove(uid);
    }

    public static AlaudaJobProperty getProperty(TopLevelItem item) {
        AlaudaJobProperty property = null;
        if(item instanceof WorkflowJob) {
            property = ((WorkflowJob) item).getProperty(WorkflowJobProperty.class);
        } else if(item instanceof WorkflowMultiBranchProject) {
            property = ((WorkflowMultiBranchProject) item).getProperties().get(MultiBranchProperty.class);
        }
        return property;
    }
}
