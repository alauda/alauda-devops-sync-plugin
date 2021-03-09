package io.alauda.jenkins.devops.sync.event;

import java.io.Serializable;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

public class EventContext implements Serializable {

  private final EventAction eventAction;

  public EventContext(WorkflowRun run) {
    eventAction = run.getAction(EventAction.class);
  }

  @Whitelisted
  public boolean isCodeRepoPushEvent() {
    return eventAction != null
        && (eventAction.getType() == EventType.CodeRepoPush
            || eventAction.getType() == EventType.PRBranch);
  }

  @Whitelisted
  public EventAction getEventAction() {
    return eventAction;
  }

  @Whitelisted
  public CodeRepoEventContext getCodeRepoPushContext() {
    if (eventAction == null) {
      return null;
    }

    Map<EventParam, String> params = eventAction.getParams();
    CodeRepoPushEventContextConstructor codeRepoPushEventContextConstructor =
        new CodeRepoPushEventContextConstructor();
    switch (eventAction.getType()) {
      case PRBranch:
        codeRepoPushEventContextConstructor.setBranch(
            params.get(EventParam.CODE_REPO_EVENT_BRANCH));
        codeRepoPushEventContextConstructor.setRepoNamespace(
            params.get(EventParam.CODE_REPO_EVENT_REPO_NAMESPACE));
        codeRepoPushEventContextConstructor.setRepoName(
            params.get(EventParam.CODE_REPO_EVENT_REPO_NAME));
        codeRepoPushEventContextConstructor.setEventType(eventAction.getType().name());
        codeRepoPushEventContextConstructor.setSourceBranch(
            params.get(EventParam.CODE_REPO_EVENT_SOURCE_BRANCH));
        codeRepoPushEventContextConstructor.setTargetBranch(
            params.get(EventParam.CODE_REPO_EVENT_TARGET_BRANCH));
        break;
      default:
      case CodeRepoPush:
        codeRepoPushEventContextConstructor.setBranch(
            params.get(EventParam.CODE_REPO_EVENT_BRANCH));
        codeRepoPushEventContextConstructor.setRepoNamespace(
            params.get(EventParam.CODE_REPO_EVENT_REPO_NAMESPACE));
        codeRepoPushEventContextConstructor.setRepoName(
            params.get(EventParam.CODE_REPO_EVENT_REPO_NAME));
        codeRepoPushEventContextConstructor.setEventType(eventAction.getType().name());
        codeRepoPushEventContextConstructor.setSourceBranch(
            params.get(EventParam.CODE_REPO_EVENT_BRANCH));
        codeRepoPushEventContextConstructor.setTargetBranch(
            params.get(EventParam.CODE_REPO_EVENT_BRANCH));
        break;
    }
    return new CodeRepoEventContext(codeRepoPushEventContextConstructor);
  }

  public static class CodeRepoPushEventContextConstructor implements Serializable {
    private String branch;
    private String repoNamespace;
    private String repoName;
    private String eventType;
    private String sourceBranch;
    private String targetBranch;

    public String getBranch() {
      return branch;
    }

    public void setBranch(String branch) {
      this.branch = branch;
    }

    public String getRepoNamespace() {
      return repoNamespace;
    }

    public void setRepoNamespace(String repoNamespace) {
      this.repoNamespace = repoNamespace;
    }

    public String getRepoName() {
      return repoName;
    }

    public void setRepoName(String repoName) {
      this.repoName = repoName;
    }

    public String getEventType() {
      return eventType;
    }

    public void setEventType(String eventType) {
      this.eventType = eventType;
    }

    public String getSourceBranch() {
      return sourceBranch;
    }

    public void setSourceBranch(String sourceBranch) {
      this.sourceBranch = sourceBranch;
    }

    public String getTargetBranch() {
      return targetBranch;
    }

    public void setTargetBranch(String targetBranch) {
      this.targetBranch = targetBranch;
    }
  }

  public static class CodeRepoEventContext implements Serializable {

    private final String branch;
    private final String repoNamespace;
    private final String repoName;
    private CodeRepoPushEventContextConstructor codeRepoPushEventContextConstructor;

    public CodeRepoEventContext(String branch, String repoNamespace, String repoName) {
      this.branch = branch;
      this.repoNamespace = repoNamespace;
      this.repoName = repoName;
    }

    public CodeRepoEventContext(
        CodeRepoPushEventContextConstructor codeRepoPushEventContextConstructor) {

      this(
          codeRepoPushEventContextConstructor.getBranch(),
          codeRepoPushEventContextConstructor.getRepoNamespace(),
          codeRepoPushEventContextConstructor.getRepoName());

      this.codeRepoPushEventContextConstructor = codeRepoPushEventContextConstructor;
    }

    @Whitelisted
    public String getBranch() {
      return branch;
    }

    @Whitelisted
    public String getRepoNamespace() {
      return repoNamespace;
    }

    @Whitelisted
    public String getRepoName() {
      return repoName;
    }

    @Whitelisted
    public String getEventType() {
      return this.codeRepoPushEventContextConstructor.getEventType();
    }

    @Whitelisted
    public String getSourceBranch() {
      return this.codeRepoPushEventContextConstructor.getSourceBranch();
    }

    @Whitelisted
    public String getTargetBranch() {
      return this.codeRepoPushEventContextConstructor.getTargetBranch();
    }
  }
}
