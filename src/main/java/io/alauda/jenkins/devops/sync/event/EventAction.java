package io.alauda.jenkins.devops.sync.event;

import hudson.model.Action;
import java.io.Serializable;
import java.util.Map;

/** EventAction will mark the build that is triggered by an devops event. */
public class EventAction implements Action, Serializable {

  private final EventType type;
  private final Map<EventParam, String> params;

  public EventAction(EventType type, Map<EventParam, String> params) {
    this.type = type;
    this.params = params;
  }

  @Override
  public String getIconFileName() {
    return null;
  }

  @Override
  public String getDisplayName() {
    return null;
  }

  @Override
  public String getUrlName() {
    return null;
  }

  public EventType getType() {
    return type;
  }

  public Map<EventParam, String> getParams() {
    return params;
  }
}
