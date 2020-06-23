package io.alauda.jenkins.devops.sync.var;

import static io.alauda.jenkins.devops.sync.listener.PipelineSyncExecutor.mountActionsPipeline;

import com.cloudbees.groovy.cps.NonCPS;
import com.google.gson.GsonBuilder;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.action.PipelineAction;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.util.PipelineUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineContext {
  private static final Logger logger = LoggerFactory.getLogger(PipelineContext.class);
  private WorkflowRun run;

  // pipeline namespace/name
  private String namespace;
  private String name;

  public PipelineContext(WorkflowRun run) {
    this.run = run;
    JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
    this.namespace = cause.getNamespace();
    this.name = cause.getName();
  }

  @Whitelisted
  public synchronized void appendInfo(String name, Object value, String type, String desc) {
    PipelineAction action = run.getAction(PipelineAction.class);
    if (action == null) {
      action = new PipelineAction();
    }

    List<PipelineAction.Information> info = action.getItems();
    info.add(new PipelineAction.Information(name, value, type, desc));
    run.addOrReplaceAction(action);
  }

  @Whitelisted
  public void appendInfo(String name, Object value, String type) {
    this.appendInfo(name, value, type, "");
  }

  @Whitelisted
  public void appendInfo(String name, Object value) {
    this.appendInfo(name, value, value.getClass().getSimpleName().toLowerCase());
  }

  /**
   * Update or create the same data When the name and type are the same, the same data is considered
   */
  @Whitelisted
  public void createOrUpdateInfo(String name, Object value, String type, String desc) {
    deleteInfo(name, type);
    appendInfo(name, value, type, desc);
  }

  @Whitelisted
  public void createOrUpdateInfo(String name, Object value, String type) {
    this.createOrUpdateInfo(name, value, type, "");
  }

  public synchronized void deleteInfo(String name, String type) {
    PipelineAction action = run.getAction(PipelineAction.class);
    if (action == null) {
      return;
    }
    List<PipelineAction.Information> list = action.getItems();
    for (int index = 0; index < list.size(); index++) {
      PipelineAction.Information item = list.get(index);
      if (item.getName().equals(name) && item.getType().equals(type)) {
        list.remove(item);
      }
    }
    run.addOrReplaceAction(action);
  }

  @NonCPS
  @Whitelisted
  public synchronized Map<String, Object> getData() {
    Map<String, Object> data = new HashMap<>();
    V1alpha1Pipeline pipeline =
        Clients.get(V1alpha1Pipeline.class).lister().namespace(namespace).get(name);
    if (pipeline != null) {
      V1alpha1Pipeline newPipeline = DeepCopyUtils.deepCopy(pipeline);
      mountActionsPipeline(run.getAllActions(), newPipeline);
      data.put("pipeline", JSONObject.fromObject(new GsonBuilder().create().toJson(newPipeline)));
    }
    return data;
  }

  @Whitelisted
  public String getNamespace() {
    return namespace;
  }

  @Whitelisted
  public String getName() {
    return name;
  }
}
