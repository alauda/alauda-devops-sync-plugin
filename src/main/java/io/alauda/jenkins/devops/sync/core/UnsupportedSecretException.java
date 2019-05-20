package io.alauda.jenkins.devops.sync.core;

/**
 * Not support Secret exception
 * @author suren
 */
public class UnsupportedSecretException extends Exception {
    public UnsupportedSecretException(String type) {
        super(String.format("not support secret type: %s", type));
    }
}
