package io.alauda.jenkins.devops.sync.exception;

import org.apache.commons.lang.StringUtils;

public class PipelineConfigConvertException extends Exception {
  private String[] causes;

  public PipelineConfigConvertException(String... causes) {
    this.causes = causes;
  }

  public String[] getCauses() {
    return causes;
  }

  @Override
  public String getMessage() {
    return StringUtils.join(getCauses(), " or ");
  }
}
