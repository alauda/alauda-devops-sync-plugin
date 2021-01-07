package io.alauda.jenkins.devops.sync.exception;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.apache.commons.lang.StringUtils;

public class PipelineConfigConvertException extends Exception {
  private String[] causes;

  public PipelineConfigConvertException(String... causes) {
    this.causes = causes;
  }

  @SuppressFBWarnings(value = "EI_EXPOSE_REP")
  public String[] getCauses() {
    return causes;
  }

  @Override
  public String getMessage() {
    return StringUtils.join(getCauses(), " or ");
  }
}
