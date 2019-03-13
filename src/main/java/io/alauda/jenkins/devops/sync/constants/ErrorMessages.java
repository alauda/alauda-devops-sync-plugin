package io.alauda.jenkins.devops.sync.constants;

/**
 * @author suren
 */
public final class ErrorMessages {
    private ErrorMessages(){}

    public static final String INVALID_TRIGGER = "contains invalid triggers";
    public static final String INVALID_CREDENTIAL = "not support credential type";
    public static final String INVALID_SOURCE = "invalid source";
    public static final String FAIL_TO_CREATE = "fail to create job in Jenkins";
    public static final String PLUGIN_ERROR = "plugin dependencies error";
}
