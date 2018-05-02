package io.alauda.jenkins.devops.sync;

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

  static synchronized void initializePipelineConfigToJobMap() {
    if (pipelineConfigToJobMap == null) {
      List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(
        WorkflowJob.class);
      pipelineConfigToJobMap = new ConcurrentHashMap<>(jobs.size());
      for (WorkflowJob job : jobs) {
        PipelineConfigProjectProperty pipelineConfigProjectProperty = job
          .getProperty(PipelineConfigProjectProperty.class);
        if (pipelineConfigProjectProperty == null) {
          continue;
        }
        String pcUid = pipelineConfigProjectProperty.getUid();
        if (isNotBlank(pcUid)) {
          pipelineConfigToJobMap.put(pcUid, job);
        }
      }
    }
  }

  static synchronized WorkflowJob getJobFromPipelineConfig(
    PipelineConfig pipelineConfig) {
    ObjectMeta meta = pipelineConfig.getMetadata();
    if (meta == null) {
      return null;
    }
    return getJobFromPipelineConfigUid(meta.getUid());
  }

  static synchronized WorkflowJob getJobFromPipelineConfigUid(String uid) {
    if (isBlank(uid)) {
      return null;
    }
    return pipelineConfigToJobMap.get(uid);
  }

  static synchronized void putJobWithPipelineConfig(WorkflowJob job,
                                                    PipelineConfig pipelineConfig) {
    if (pipelineConfig == null) {
      throw new IllegalArgumentException("PipelineConfig cannot be null");
    }
    if (job == null) {
      throw new IllegalArgumentException("Job cannot be null");
    }
    ObjectMeta meta = pipelineConfig.getMetadata();
    if (meta == null) {
      throw new IllegalArgumentException(
        "PipelineConfig must contain valid metadata");
    }
    putJobWithPipelineConfigUid(job, meta.getUid());
  }

  static synchronized void putJobWithPipelineConfigUid(WorkflowJob job,
                                                       String uid) {
    if (isBlank(uid)) {
      throw new IllegalArgumentException(
        "PipelineConfig uid must not be blank");
    }
    pipelineConfigToJobMap.put(uid, job);
  }

  static synchronized void removeJobWithPipelineConfig(PipelineConfig pipelineConfig) {
    if (pipelineConfig == null) {
      throw new IllegalArgumentException("PipelineConfig cannot be null");
    }
    ObjectMeta meta = pipelineConfig.getMetadata();
    if (meta == null) {
      throw new IllegalArgumentException(
        "PipelineConfig must contain valid metadata");
    }
    removeJobWithPipelineConfigUid(meta.getUid());
  }

  static synchronized void removeJobWithPipelineConfigUid(String uid) {
    if (isBlank(uid)) {
      throw new IllegalArgumentException(
        "PipelineConfig uid must not be blank");
    }
    pipelineConfigToJobMap.remove(uid);
  }

}
