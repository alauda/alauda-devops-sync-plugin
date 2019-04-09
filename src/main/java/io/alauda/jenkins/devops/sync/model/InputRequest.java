package io.alauda.jenkins.devops.sync.model;

import java.util.List;

public class InputRequest {
    private String id;
    private String buildID;
    private String message;
    private String submitter;
    private List<InputRequestParam> params;
    private String status;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getBuildID() {
        return buildID;
    }

    public void setBuildID(String buildID) {
        this.buildID = buildID;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSubmitter() {
        return submitter;
    }

    public void setSubmitter(String submitter) {
        this.submitter = submitter;
    }

    public List<InputRequestParam> getParams() {
        return params;
    }

    public void setParams(List<InputRequestParam> params) {
        this.params = params;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
