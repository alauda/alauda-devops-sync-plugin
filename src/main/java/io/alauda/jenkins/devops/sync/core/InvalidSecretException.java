package io.alauda.jenkins.devops.sync.core;

/**
 * Not support Secret exception
 * @author suren
 */
public class InvalidSecretException extends RuntimeException {
    public InvalidSecretException(String type) {
        super(String.format("not support secret type: %s", type));
    }
}
