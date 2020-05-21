package io.alauda.jenkins.devops.sync.util;

import hudson.model.Job;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigProjectProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
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
}
