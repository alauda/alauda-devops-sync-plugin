package io.alauda.jenkins.devops.sync;

public class ActivityWaitException extends RuntimeException {
    public ActivityWaitException(String message, Throwable cause) {
        super(message, cause);
    }
}
