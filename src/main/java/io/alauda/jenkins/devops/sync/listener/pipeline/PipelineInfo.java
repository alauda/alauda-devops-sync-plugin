package io.alauda.jenkins.devops.sync.listener.pipeline;

import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

// TODO should remove this class once we change the V1alphaPipeline schema
public class PipelineInfo {

  private PipelineInfoSpec spec;
  private PipelineInfoStatus status;

  public PipelineInfoSpec getSpec() {
    return spec;
  }

  public void setSpec(PipelineInfoSpec spec) {
    this.spec = spec;
  }

  public PipelineInfoStatus getStatus() {
    return status;
  }

  public void setStatus(PipelineInfoStatus status) {
    this.status = status;
  }

  public static class PipelineInfoSpec {

    private List<V1alpha1PipelineParameter> parameters;
    private List<PipelineCause> causes;
    private List<PipelineDownstream> downstreams;

    public List<V1alpha1PipelineParameter> getParameters() {
      return parameters;
    }

    public void setParameters(List<V1alpha1PipelineParameter> parameters) {
      this.parameters = parameters;
    }

    public List<PipelineCause> getCauses() {
      return causes;
    }

    public void setCauses(List<PipelineCause> causes) {
      this.causes = causes;
    }

    public List<PipelineDownstream> getDownstreams() {
      return downstreams;
    }

    public void setDownstreams(List<PipelineDownstream> downstreams) {
      this.downstreams = downstreams;
    }
  }

  public static class PipelineCause {

    private PipelineCauseType type;
    private String description;
    private Map<String, String> properties = new HashMap<>();

    public PipelineCauseType getType() {
      return type;
    }

    public void setType(PipelineCauseType type) {
      this.type = type;
    }

    public String getDescription() {
      return description;
    }

    public void setDescription(String description) {
      this.description = description;
    }

    public Map<String, String> getProperties() {
      return properties;
    }

    public String setProperty(String key, String value) {
      return properties.put(key, value);
    }
  }

  public static class PipelineDownstream {

    private String downstreamUrl;
    private Map<String, String> properties = new HashMap<>();

    public String getDownstreamUrl() {
      return downstreamUrl;
    }

    public void setDownstreamUrl(String downstreamUrl) {
      this.downstreamUrl = downstreamUrl;
    }

    public Map<String, String> getProperties() {
      return properties;
    }

    public void setProperties(Map<String, String> properties) {
      this.properties = properties;
    }

    public String putDownstreamInfo(String key, String value) {
      return properties.put(key, value);
    }
  }

  public enum PipelineCauseType {
    UPSTREAM,
    PIPELINE,
    REPLAY
  }

  public static class PipelineCauseUpstreamProperty {
    public static final String BUILD = "build";
    public static final String PROJECT = "project";
    public static final String URL = "url";
  }

  public static class PipelineCauseReplayProperty {
    public static final String ORIGIN_NUM = "originNumber";
  }

  public static class PipelineInfoStatus {

    private PipelineStatusJenkins statusJenkins;
    private String status;
    private String result;

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public String getResult() {
      return result;
    }

    public void setResult(String result) {
      this.result = result;
    }

    public PipelineStatusJenkins getStatusJenkins() {
      return statusJenkins;
    }

    public void setStatusJenkins(PipelineStatusJenkins statusJenkins) {
      this.statusJenkins = statusJenkins;
    }
  }

  public static class PipelineStatusJenkins {

    private String status;
    private String result;
    private String build;
    private String stages;
    private List<PipelineStatusJenkinsArtifact> artifacts;
    private List<PipelineSCMChange> changes;
    private List<PipelineSCMInfo> scmInfos;

    private long buildingDurationInMillis;
    private long waitingDurationInMillis;

    public long getBuildingDurationInMillis() {
      return buildingDurationInMillis;
    }

    public void setBuildingDurationInMillis(long buildingDurationInMillis) {
      this.buildingDurationInMillis = buildingDurationInMillis;
    }

    public long getWaitingDurationInMillis() {
      return waitingDurationInMillis;
    }

    public void setWaitingDurationInMillis(long waitingDurationInMillis) {
      this.waitingDurationInMillis = waitingDurationInMillis;
    }

    public String getStatus() {
      return status;
    }

    public void setStatus(String status) {
      this.status = status;
    }

    public String getResult() {
      return result;
    }

    public void setResult(String result) {
      this.result = result;
    }

    public String getBuild() {
      return build;
    }

    public void setBuild(String build) {
      this.build = build;
    }

    public String getStages() {
      return stages;
    }

    public void setStages(String stages) {
      this.stages = stages;
    }

    public List<PipelineStatusJenkinsArtifact> getArtifacts() {
      return artifacts;
    }

    public void setArtifacts(List<PipelineStatusJenkinsArtifact> artifacts) {
      this.artifacts = artifacts;
    }

    public List<PipelineSCMChange> getChanges() {
      return changes;
    }

    public void setChanges(List<PipelineSCMChange> changes) {
      this.changes = changes;
    }

    public List<PipelineSCMInfo> getScmInfos() {
      return scmInfos;
    }

    public void setScmInfos(List<PipelineSCMInfo> scmInfos) {
      this.scmInfos = scmInfos;
    }
  }

  public static class PipelineStatusJenkinsArtifact {

    public PipelineStatusJenkinsArtifact(String name) {
      this.name = name;
    }

    private String name;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }
  }

  public static class PipelineSCMChange {

    private String commit;
    private String commitMessage;
    private String author;

    public PipelineSCMChange(String commit, String commitMessage, String author) {
      this.commit = commit;
      this.commitMessage = commitMessage;
      this.author = author;
    }

    public String getCommit() {
      return commit;
    }

    public void setCommit(String commit) {
      this.commit = commit;
    }

    public String getCommitMessage() {
      return commitMessage;
    }

    public void setCommitMessage(String commitMessage) {
      this.commitMessage = commitMessage;
    }

    public String getAuthor() {
      return author;
    }

    public void setAuthor(String author) {
      this.author = author;
    }
  }

  public static class PipelineSCMInfo {

    private PipelineSCMInfoPR prInfo;
    private PipelineSCMInfoBranch branchInfo;

    public PipelineSCMInfoPR getPrInfo() {
      return prInfo;
    }

    public void setPrInfo(PipelineSCMInfoPR prInfo) {
      this.prInfo = prInfo;
    }

    public PipelineSCMInfoBranch getBranchInfo() {
      return branchInfo;
    }

    public void setBranchInfo(PipelineSCMInfoBranch branchInfo) {
      this.branchInfo = branchInfo;
    }
  }

  public static class PipelineSCMInfoPR {

    private String source;
    private String target;
    private String title;
    private String id;
    private String url;

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = source;
    }

    public String getTarget() {
      return target;
    }

    public void setTarget(String target) {
      this.target = target;
    }

    public String getTitle() {
      return title;
    }

    public void setTitle(String title) {
      this.title = title;
    }

    public String getId() {
      return id;
    }

    public void setId(String id) {
      this.id = id;
    }

    public String getUrl() {
      return url;
    }

    public void setUrl(String url) {
      this.url = url;
    }
  }

  public static class PipelineSCMInfoBranch {

    private String branch;

    public String getBranch() {
      return branch;
    }

    public void setBranch(String branch) {
      this.branch = branch;
    }
  }
}
