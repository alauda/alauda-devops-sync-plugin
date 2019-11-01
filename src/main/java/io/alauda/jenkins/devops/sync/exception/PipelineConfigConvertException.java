package io.alauda.jenkins.devops.sync.exception;

public class PipelineConfigConvertException extends Exception {
  private String[] causes;

  public PipelineConfigConvertException(String... causes) {
    this.causes = causes;
  }

  public String[] getCauses() {
    return causes;
  }
}
