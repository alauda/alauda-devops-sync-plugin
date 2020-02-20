package io.alauda.jenkins.devops.sync.exception;

public class PipelineException extends Exception {

  public PipelineException() {}

  public PipelineException(String message) {
    super(message);
  }

  public PipelineException(String message, Throwable cause) {
    super(message, cause);
  }

  public PipelineException(Throwable cause) {
    super(cause);
  }

  public PipelineException(
      String message, Throwable cause, boolean enableSuppression, boolean writableStackTrace) {
    super(message, cause, enableSuppression, writableStackTrace);
  }
}
