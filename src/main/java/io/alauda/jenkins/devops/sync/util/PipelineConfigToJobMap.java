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
import java.util.logging.Logger;

import static org.apache.commons.lang.StringUtils.isBlank;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class PipelineConfigToJobMap {
    private static final Logger LOGGER = Logger.getLogger(PipelineConfigToJobMap.class.getName());

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
            WorkflowJobProperty property = WorkflowJobUtils.getAlaudaProperty(job);

            return (property != null && isNotBlank(property.getUid()));
        }).forEach(job -> {
            WorkflowJobProperty property = job.getProperty(WorkflowJobProperty.class);
            if(property == null) {
                property = job.getProperty(PipelineConfigProjectProperty.class);
            }
            String uid = property.getUid();
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
    public static synchronized WorkflowJob getJobFromPipelineConfig(V1alpha1PipelineConfig pipelineConfig) {
        V1ObjectMeta meta = pipelineConfig.getMetadata();
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

    public static synchronized WorkflowMultiBranchProject getMultiBranchByPC(V1alpha1PipelineConfig pc){
        V1ObjectMeta meta = pc.getMetadata();
        if (meta == null) {
            return null;
        }
        return getMultiBranchById(meta.getUid());
    }

    public static TopLevelItem getItemById(String uid) {
        return pipelineConfigToJobMap.get(uid);
    }

    public static synchronized TopLevelItem getItemByPC(V1alpha1PipelineConfig pc) {
        V1ObjectMeta meta = pc.getMetadata();
        if (meta == null) {
            return null;
        }
        return getItemById(meta.getUid());
    }

    public static synchronized void putJobWithPipelineConfig(TopLevelItem job, V1alpha1PipelineConfig pipelineConfig) {
        if (pipelineConfig == null) {
            throw new IllegalArgumentException("PipelineConfig cannot be null");
        }
        if (job == null) {
            throw new IllegalArgumentException("Job cannot be null");
        }
        V1ObjectMeta meta = pipelineConfig.getMetadata();
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

        LOGGER.info(String.format("Add job %s, uid %s, total cache number %d", job.getFullName(), uid, pipelineConfigToJobMap.size()));
    }

    public static synchronized void removeJobWithPipelineConfig(V1alpha1PipelineConfig pipelineConfig) {
        if (pipelineConfig == null) {
            throw new IllegalArgumentException("PipelineConfig cannot be null");
        }
        V1ObjectMeta meta = pipelineConfig.getMetadata();
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

        LOGGER.info(String.format("Remove job uid %s, total cache number %d", uid, pipelineConfigToJobMap.size()));
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
