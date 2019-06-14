package io.alauda.jenkins.devops.sync.util;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.security.ACL;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineSource;
import io.alauda.kubernetes.api.model.SecretKeySetRef;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.plaincredentials.StringCredentials;

import java.util.Collections;
import java.util.logging.Logger;

public final class CredentialsUtils {
    private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());

    private CredentialsUtils(){}


    public static String getSCMSourceCredentialsId(PipelineConfig pipelineConfig) {
        PipelineSource source = pipelineConfig.getSpec().getSource();
        if (source == null) {
            return "";
        }

        SecretKeySetRef secretRef = source.getSecret();
        if (secretRef == null) {
            return "";
        }

        return secretRef.getNamespace() + "-" + secretRef.getName();
    }

    // getCurrentToken returns the ServiceAccount token currently selected by
    // the user. A return value of empty string
    // implies no token is configured.
    public static String getCurrentToken() {
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        if (config == null) {
            logger.info("global plugin configuration is null");
            return "";
        }

        String credentialsId = config.getCredentialsId();
        if ("".equals(credentialsId)) {
            logger.fine("no credential for alauda sync config.");
            return "";
        }

        String token = getToken(credentialsId);
        if(token == null) {
            logger.info("cannot get a valid token");
            return "";
        }

        return token;
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
