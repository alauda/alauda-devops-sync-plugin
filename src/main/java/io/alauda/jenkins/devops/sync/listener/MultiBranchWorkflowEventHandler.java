package io.alauda.jenkins.devops.sync.listener;

import static io.alauda.jenkins.devops.sync.constants.Annotations.*;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import io.alauda.jenkins.devops.sync.multiBranch.PullRequest;
import io.alauda.jenkins.devops.sync.util.WorkflowJobUtils;
import io.kubernetes.client.models.V1ObjectMeta;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

@Extension
@Restricted(DoNotUse.class)
public class MultiBranchWorkflowEventHandler implements ItemEventHandler<WorkflowJob> {
  private static final Logger logger =
      Logger.getLogger(MultiBranchWorkflowEventHandler.class.getName());

  @Override
  public boolean accept(Item item) {
    if (item == null) {
      return false;
    }

    ItemGroup<? extends Item> parent = item.getParent();
    return (parent instanceof WorkflowMultiBranchProject);
  }

  @Override
  public void onCreated(WorkflowJob item) {
    WorkflowJobUtils.updateBranchAndPRAnnotations(item);
    //        synchronized (item) {
    //            BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
    //
    //            String scmURL = "";
    //            ObjectMetadataAction metadataAction = item.getAction(ObjectMetadataAction.class);
    //            if (metadataAction != null) {
    //                scmURL = metadataAction.getObjectUrl();
    //            }
    //
    //            if (pro != null) {
    //                String branchName = pro.getBranch().getName();
    //                PipelineConfigUpdater pipelineConfigUpdater = new PipelineConfigUpdater(item,
    // branchName);
    //
    //                PullRequest pr = PipelineGenerator.getPR(item);
    //                if (pr != null) {
    //                    // we consider it as a pr
    //                    pr.setUrl(scmURL);
    //                    pipelineConfigUpdater.addPRAnnotation(pr);
    //                    logger.info(String.format("add a pr %s", branchName));
    //                } else {
    //                    pipelineConfigUpdater.addBranchAnnotation(scmURL);
    //                    logger.info(String.format("add a branch %s", branchName));
    //                }
    //
    //                pipelineConfigUpdater.addParameters();
    //                pipelineConfigUpdater.commit();
    //            }
    //        }
  }

  class BranchItem {
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

  @Override
  public void onUpdated(WorkflowJob item) {
    WorkflowJobUtils.updateBranchAndPRAnnotations(item);
    //        synchronized (MultiBranchWorkflowEventHandler.class) {
    //            BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
    //
    //            String scmURL = "";
    //            ObjectMetadataAction metadataAction = item.getAction(ObjectMetadataAction.class);
    //            if(metadataAction != null) {
    //                scmURL = metadataAction.getObjectUrl();
    //            }
    //            if(pro != null) {
    //                String branchName = pro.getBranch().getName();
    //
    //                PipelineConfigUpdater pipelineConfigUpdater = new PipelineConfigUpdater(item,
    // branchName);
    //                if(item.isDisabled()) {
    //                    // it's a stale pipeline for multi-branch
    //                    PullRequest pr = PipelineGenerator.getPR(item);
    //                    if(pr != null) {
    //                        pipelineConfigUpdater.delPRAnnotation();
    //                        pipelineConfigUpdater.addStalePRAnnotation();
    //                        logger.info(String.format("disable a pr %s", branchName));
    //                    } else {
    //                        pipelineConfigUpdater.delBranchAnnotation();
    //                        pipelineConfigUpdater.addAnnotation(MULTI_BRANCH_STALE_BRANCH,
    // branchName);
    //                        logger.info(String.format("disable a branch %s", branchName));
    //                    }
    //                } else {
    //                    // when the deleted branch had been restored
    //                    PullRequest pr = PipelineGenerator.getPR(item);
    //                    if(pr != null) {
    //                        pr.setUrl(scmURL);
    //                        pipelineConfigUpdater.delStalePRAnnotation();
    //                        pipelineConfigUpdater.addPRAnnotation(pr, branchName);
    //                        logger.info(String.format("enable a pr %s", branchName));
    //                    } else {
    //                        pipelineConfigUpdater.delStaleBranchAnnotation();
    //                        pipelineConfigUpdater.addBranchAnnotation(scmURL);
    //                        logger.info(String.format("enable a branch %s", branchName));
    //                    }
    //                }
    //
    //                pipelineConfigUpdater.commit();
    //            }
    //        }
  }

  @Override
  public void onDeleted(WorkflowJob item) {
    WorkflowJobUtils.updateBranchAndPRAnnotations(item, true);
    //        synchronized (MultiBranchWorkflowEventHandler.class) {
    //            BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
    //            if (pro != null) {
    //                String name = pro.getBranch().getEncodedName();
    //
    //                PipelineConfigUpdater pipelineConfigUpdater = new PipelineConfigUpdater(item,
    // name);
    //                PullRequest pr = PipelineGenerator.getPR(item);
    //                if (pr != null) {
    //                    pipelineConfigUpdater.delStalePRAnnotation();
    //                    logger.info(String.format("del a stale pr %s", name));
    //                } else {
    //                    pipelineConfigUpdater.delStaleBranchAnnotation();
    //                    logger.info(String.format("del a stale branch %s", name));
    //                }
    //
    //                pipelineConfigUpdater.delParameters();
    //                pipelineConfigUpdater.commit();
    //            }
    //        }
  }

  private class PipelineConfigUpdater {
    private WorkflowJob job;
    private String branchName;

    private V1alpha1PipelineConfig oldPC;
    private V1alpha1PipelineConfig newPC;

    PipelineConfigUpdater(WorkflowJob job, String branchName) {
      this.job = job;
      this.branchName = branchName;

      WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) job.getParent();
      oldPC = getPipelineConfig(parent);
      if (oldPC == null) {
        return;
      }
      newPC = DeepCopyUtils.deepCopy(oldPC);
    }

    void addPRAnnotation(PullRequest pr) {
      addAnnotation(MULTI_BRANCH_PR, branchName);
      setAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName),
          pr);
    }

    void addBranchAnnotation(String scmURL) {
      addAnnotation(MULTI_BRANCH_BRANCH, branchName);
      setAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName)
              + ".url",
          scmURL);
    }

    void delPRAnnotation() {
      delAnnotation(MULTI_BRANCH_PR, branchName);
    }

    void delStalePRAnnotation() {
      delAnnotation(MULTI_BRANCH_STALE_PR, branchName);
      delAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName));
      delAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName)
              + ".url");
    }

    void addPRAnnotation(PullRequest pr, String prName) {
      addAnnotation(MULTI_BRANCH_PR, prName);
      setAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + prName,
          pr);
    }

    void delStaleBranchAnnotation() {
      delAnnotation(MULTI_BRANCH_STALE_BRANCH, branchName);
      delAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName)
              + ".url");
    }

    void delAnnotation(String annotation, String name) {
      V1ObjectMeta meta = newPC.getMetadata();
      Map<String, String> annotations = meta.getAnnotations();
      if (annotations == null) {
        return;
      }

      String branchJson = annotations.get(annotation);
      if (branchJson == null) {
        return;
      }

      try {
        JSONArray jsonArray = JSONArray.fromObject(branchJson);
        jsonArray.remove(name);

        annotations.put(annotation, jsonArray.toString());
      } catch (JSONException e) {
        e.printStackTrace();
      }
    }

    void delAnnotation(String annotation) {
      V1ObjectMeta meta = newPC.getMetadata();
      Map<String, String> annotations = meta.getAnnotations();
      if (annotations == null) {
        annotations = new HashMap<>();
        meta.setAnnotations(annotations);
      }
      annotations.remove(annotation);
    }

    void delBranchAnnotation() {
      delAnnotation(MULTI_BRANCH_BRANCH, branchName);
    }

    void addStalePRAnnotation() {
      addAnnotation(MULTI_BRANCH_STALE_PR, branchName);
    }

    void setAnnotation(final String annotation, Object obj) {
      V1ObjectMeta meta = newPC.getMetadata();
      Map<String, String> annotations = meta.getAnnotations();
      if (annotations == null) {
        annotations = new HashMap<>();
        meta.setAnnotations(annotations);
      }

      String jsonStr = null;
      if (obj instanceof String) {
        jsonStr = obj.toString();
      } else if (obj instanceof List) {
        jsonStr = JSONArray.fromObject(obj).toString();
      } else {
        jsonStr = JSONObject.fromObject(obj).toString();
      }

      annotations.put(annotation, jsonStr);
    }

    void addAnnotation(final String annotation, String value) {
      V1ObjectMeta meta = newPC.getMetadata();
      String branchJson = null;

      Map<String, String> annotations = meta.getAnnotations();
      if (annotations != null) {
        branchJson = annotations.get(annotation);
      }

      JSONArray jsonArray;
      if (branchJson == null || "".equalsIgnoreCase(branchJson)) {
        jsonArray = new JSONArray();
      } else {
        try {
          jsonArray = JSONArray.fromObject(branchJson);
        } catch (JSONException e) {
          e.printStackTrace();
          jsonArray = new JSONArray();
        }
      }

      if (!jsonArray.contains(value)) {
        jsonArray.add(value);
      }
      meta.putAnnotationsItem(annotation, jsonArray.toString());

      logger.info(meta.getAnnotations().get(annotation));
    }

    void addParameters() {
      List<V1alpha1PipelineParameter> pipelineParameters =
          PipelineConfigToJobMapper.getPipelineParameter(job);
      setAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName)
              + ".params",
          pipelineParameters);
    }

    void delParameters() {
      delAnnotation(
          ResourceControllerManager.getControllerManager().getFormatedAnnotation("jenkins.")
              + annotationKeySpec(branchName)
              + ".params");
    }

    void commit() {
      Clients.get(V1alpha1PipelineConfig.class).update(oldPC, newPC);
    }
  }

  private String toJSON(Object obj) {
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

  String annotationKeySpec(String key) {
    if (key == null) {
      return null;
    }

    return key.replaceAll("[^0-9a-zA-Z-]", "-");
  }

  V1alpha1PipelineConfig getPipelineConfig(WorkflowMultiBranchProject job) {
    AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
    if (pro == null) {
      logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
      return null;
    }

    String namespace = pro.getNamespace();
    String name = pro.getName();

    return Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
  }
}
