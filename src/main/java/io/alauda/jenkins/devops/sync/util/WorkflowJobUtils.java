package io.alauda.jenkins.devops.sync.util;

import static io.alauda.jenkins.devops.sync.constants.Annotations.*;

import hudson.model.Job;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigProjectProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import io.alauda.jenkins.devops.sync.multiBranch.PullRequest;
import io.kubernetes.client.models.V1ObjectMeta;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

public final class WorkflowJobUtils {
  private static final Logger logger = Logger.getLogger(WorkflowJobUtils.class.getName());

  private WorkflowJobUtils() {}

  public static boolean hasAlaudaProperty(Job job) {
    WorkflowJobProperty property = getAlaudaProperty(job);

    return (property != null);
  }

  public static boolean hasNotAlaudaProperty(Job job) {
    return !hasAlaudaProperty(job);
  }

  public static WorkflowJobProperty getAlaudaProperty(Job job) {
    WorkflowJobProperty property = (WorkflowJobProperty) job.getProperty(WorkflowJobProperty.class);
    if (property == null) {
      property = (WorkflowJobProperty) job.getProperty(PipelineConfigProjectProperty.class);
    }
    return property;
  }

  public static boolean parametersHasChange(WorkflowJob item) {
    WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();
    V1alpha1PipelineConfig pc = getPipelineConfig(parent);
    if (pc == null) {
      return false;
    }

    BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
    if (pro == null) {
      return false;
    }

    Map<String, String> annotations = pc.getMetadata().getAnnotations();
    if (annotations == null) {
      return false;
    }

    String branchName = pro.getBranch().getName();
    String paramKey =
        ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.").get()
            + annotationKeySpec(branchName)
            + ".params";
    List<V1alpha1PipelineParameter> pipelineParameters =
        PipelineConfigToJobMapper.getPipelineParameter(item);
    return !StringUtils.equals(toJSON(pipelineParameters), annotations.get(paramKey));
  }

  public static synchronized void updateBranchAndPRAnnotations(@Nonnull WorkflowJob item) {
    updateBranchAndPRAnnotations(item, false);
  }

  public static synchronized void updateBranchAndPRAnnotations(
      @Nonnull WorkflowJob item, boolean jobDeleted) {
    WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();
    V1alpha1PipelineConfig oldPC = getPipelineConfig(parent);
    if (oldPC == null) {
      return;
    }
    V1alpha1PipelineConfig newPC = DeepCopyUtils.deepCopy(oldPC);
    V1ObjectMeta meta = newPC.getMetadata();

    // clean up all annotations which start with alauda.io/jenkins
    clearBranchAndPRAnnotations(meta.getAnnotations());

    BranchItem branchItem = new BranchItem();
    Collection<? extends Job> allJobs = parent.getAllJobs();
    // if this item is deleted, we should remove it from jobs
    if (jobDeleted) {
      allJobs.remove(item);
    }

    for (Job job : allJobs) {
      if (!(job instanceof WorkflowJob)) {
        continue;
      }

      WorkflowJob wfJob = (WorkflowJob) job;
      BranchJobProperty pro = wfJob.getProperty(BranchJobProperty.class);
      if (pro == null) {
        continue;
      }

      String scmURL = "";
      ObjectMetadataAction metadataAction = item.getAction(ObjectMetadataAction.class);
      if (metadataAction != null) {
        scmURL = metadataAction.getObjectUrl();
      }

      String branchName = pro.getBranch().getName();
      PullRequest pr = PipelineGenerator.getPR(wfJob);
      if (pr != null) {
        // we consider it as a pr
        pr.setUrl(scmURL);
        putIfNotEmpty(
            meta,
            ResourceControllerManager.getControllerManager()
                    .getFormattedAnnotation("jenkins.")
                    .get()
                + annotationKeySpec(branchName),
            toJSON(pr));
      } else {
        putIfNotEmpty(
            meta,
            ResourceControllerManager.getControllerManager()
                    .getFormattedAnnotation("jenkins.")
                    .get()
                + annotationKeySpec(branchName)
                + ".url",
            scmURL);
      }

      branchItem.add(wfJob, pr != null, branchName);

      List<V1alpha1PipelineParameter> pipelineParameters =
          PipelineConfigToJobMapper.getPipelineParameter(wfJob);
      putIfNotEmpty(
          meta,
          ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins.").get()
              + annotationKeySpec(branchName)
              + ".params",
          toJSON(pipelineParameters));
    }

    putIfNotEmpty(meta, MULTI_BRANCH_PR.get().toString(), branchItem.getPrList());
    putIfNotEmpty(meta, MULTI_BRANCH_STALE_PR.get().toString(), branchItem.getStalePRList());
    putIfNotEmpty(meta, MULTI_BRANCH_BRANCH.get().toString(), branchItem.getBranchList());
    putIfNotEmpty(
        meta, MULTI_BRANCH_STALE_BRANCH.get().toString(), branchItem.getStaleBranchList());

    Clients.get(V1alpha1PipelineConfig.class).update(oldPC, newPC);
  }

  private static void clearBranchAndPRAnnotations(Map<String, String> annotations) {
    if (annotations == null) {
      return;
    }

    annotations
        .entrySet()
        .removeIf(
            entry ->
                entry
                    .getKey()
                    .startsWith(
                        ResourceControllerManager.getControllerManager().getFormattedAnnotation("jenkins")
                            .get()));
  }

  private static void putIfNotEmpty(V1ObjectMeta meta, String key, List<?> value) {
    if (value == null || value.isEmpty()) {
      return;
    }
    meta.putAnnotationsItem(key, toJSON(value));
  }

  private static void putIfNotEmpty(V1ObjectMeta meta, String key, String value) {
    if (StringUtils.isNotEmpty(value)) {
      meta.putAnnotationsItem(key, value);
    }
  }

  private static String toJSON(Object obj) {
    String jsonStr = null;
    if (obj instanceof String) {
      jsonStr = obj.toString();
    } else if (obj instanceof List) {
      jsonStr = JSONArray.fromObject(obj).toString();
    } else {
      jsonStr = JSONObject.fromObject(obj).toString();
    }
    return jsonStr;
  }

  private static String annotationKeySpec(String key) {
    if (key == null) {
      return null;
    }

    return key.replaceAll("[^0-9a-zA-Z-]", "-");
  }

  private static V1alpha1PipelineConfig getPipelineConfig(WorkflowMultiBranchProject job) {
    AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
    if (pro == null) {
      logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
      return null;
    }

    String namespace = pro.getNamespace();
    String name = pro.getName();

    return Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
  }

  static class BranchItem {
    private List<String> branchList = new ArrayList<>();
    private List<String> staleBranchList = new ArrayList<>();
    private List<String> prList = new ArrayList<>();
    private List<String> stalePRList = new ArrayList<>();

    public void add(WorkflowJob wfJob, boolean isPR, String branchName) {
      if (wfJob.isDisabled() && isPR) {
        stalePRList.add(branchName);
      } else if (wfJob.isDisabled() && !isPR) {
        staleBranchList.add(branchName);
      } else if (!wfJob.isDisabled() && !isPR) {
        branchList.add(branchName);
      } else {
        prList.add(branchName);
      }
    }

    public List<String> getBranchList() {
      return branchList;
    }

    public List<String> getStaleBranchList() {
      return staleBranchList;
    }

    public List<String> getPrList() {
      return prList;
    }

    public List<String> getStalePRList() {
      return stalePRList;
    }
  }
}
