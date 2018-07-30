package io.alauda.jenkins.devops.sync.credential;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import hudson.Extension;
import hudson.util.Secret;
import org.kohsuke.stapler.DataBoundConstructor;

public class AlaudaToken extends BaseStandardCredentials {

    private final Secret secret;

    @DataBoundConstructor
    public AlaudaToken(CredentialsScope scope, String id,
                       String description, Secret secret) {
        super(scope, id, description);
        this.secret = secret;
    }

    public String getToken() {
        return secret.getPlainText();
    }

    public Secret getSecret() {
        return secret;
    }

    @Extension
    public static class DescriptorImpl extends
            BaseStandardCredentialsDescriptor {
        @Override
        public String getDisplayName() {
            return "Alauda Token for Alauda Sync Plugin";
        }
    }

}
