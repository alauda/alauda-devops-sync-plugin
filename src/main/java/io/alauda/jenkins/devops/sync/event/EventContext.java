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
    return eventAction != null && eventAction.getType() == EventType.CodeRepoPush;
  }

  @Whitelisted
  public CodeRepoPushEventContext getCodeRepoPushContext() {
    if (eventAction == null) {
      return null;
    }

    Map<EventParam, String> params = eventAction.getParams();

    return new CodeRepoPushEventContext(
        params.get(EventParam.CODE_REPO_EVENT_BRANCH),
        params.get(EventParam.CODE_REPO_EVENT_REPO_NAMESPACE),
        params.get(EventParam.CODE_REPO_EVENT_REPO_NAME));
  }

  public static class CodeRepoPushEventContext implements Serializable {

    private final String branch;
    private final String repoNamespace;
    private final String repoName;

    public CodeRepoPushEventContext(String branch, String repoNamespace, String repoName) {
      this.branch = branch;
      this.repoNamespace = repoNamespace;
      this.repoName = repoName;
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
  }
}
