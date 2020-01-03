package io.alauda.jenkins.devops.sync.core;

import java.util.HashMap;
import java.util.Map;

/** To power the result of an action */
public class ActionResult {
  public enum Status {
    /** Action was done successfully */
    SUCCESS,
    /** Action wasn't done, error occurred */
    FAILURE,
    /** Action wasn't done for some reasons, just skip it */
    SKIP,
    /** Action was repeated */
    REPEAT
  }

  public static ActionResult SUCCESS() {
    return new ActionResult(Status.SUCCESS);
  }

  public static ActionResult FAILURE() {
    return new ActionResult(Status.FAILURE);
  }

  private Status status;
  private String message;
  private Map<String, Object> data = new HashMap<>();

  public ActionResult(Status status) {
    this.status = status;
  }

  public ActionResult(Status status, String message) {
    this.status = status;
    this.message = message;
  }

  public ActionResult(Status status, String message, String... params) {
    this.status = status;
    this.message = String.format(message, params);
  }

  public Status getStatus() {
    return status;
  }

  public void setStatus(Status status) {
    this.status = status;
  }

  public Map<String, Object> getData() {
    return data;
  }

  public void setData(Map<String, Object> data) {
    this.data = data;
  }

  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
