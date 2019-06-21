package io.alauda.jenkins.devops.sync.util;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineSource;
import io.alauda.devops.java.client.models.V1alpha1SecretKeySetRef;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.Collections;
import java.util.logging.Logger;

public final class CredentialsUtils {
    private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());

    private CredentialsUtils(){}


    public static String getSCMSourceCredentialsId(V1alpha1PipelineConfig pipelineConfig) {
        V1alpha1PipelineSource source = pipelineConfig.getSpec().getSource();
        if (source == null) {
            return "";
        }

        V1alpha1SecretKeySetRef secretRef = source.getSecret();
        if (secretRef == null) {
            return "";
        }

        return secretRef.getNamespace() + "-" + secretRef.getName();
    }

    public static String getToken(String credentialId) {
        StringCredentials token = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(StringCredentials.class,
                        Jenkins.getInstance(), ACL.SYSTEM,
                        Collections.<DomainRequirement> emptyList()),
                CredentialsMatchers.withId(credentialId));

        if (token != null) {
            return token.getSecret().getPlainText();
        } else {
            return null;
        }
    }


}
