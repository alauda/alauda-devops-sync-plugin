package io.alauda.jenkins.devops.sync.util;

import hudson.remoting.Base64;
import io.alauda.devops.api.model.BuildConfig;
import io.alauda.devops.api.model.BuildConfigBuilder;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.jenkins.devops.sync.core.InvalidSecretException;
import io.alauda.kubernetes.api.model.Secret;
import io.alauda.kubernetes.api.model.SecretBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_SECRETS_TYPE_OPAQUE;
import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_SECRETS_TYPE_SERVICE_ACCOUNT_TOKEN;
import static org.junit.Assert.*;

public class CredentialsUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void getToken() {
        // fake CredentialTest id test
        assertNull(CredentialsUtils.getToken("fake"));
    }

    @Test
    public void hasCredentials() throws Exception {
        AlaudaUtils.shutdownAlaudaClient();

        assertFalse(CredentialsUtils.hasCredentials());

        assertNull(CredentialsUtils.lookupCredentials(""));

        assertNull(CredentialsUtils.upsertCredential(null));

        try {
            Secret secret = new SecretBuilder().withType("type").editOrNewMetadata()
                    .withName("name").withNamespace("ns").endMetadata().build();
            CredentialsUtils.upsertCredential(secret);
        }catch (Exception e) {
            assertEquals(InvalidSecretException.class, e.getClass());
        }

        try {
            Secret secret = new SecretBuilder().withType(ALAUDA_DEVOPS_SECRETS_TYPE_OPAQUE).editOrNewMetadata()
                    .withName("name").withNamespace("ns").endMetadata().build();
            CredentialsUtils.upsertCredential(secret);
        }catch (Exception e) {
            assertEquals(InvalidSecretException.class, e.getClass());
        }

        {
            Map<String, String> data = new HashMap<>();
            data.put("token", Base64.encode("data".getBytes()));

            Secret secret = new SecretBuilder().withType(ALAUDA_DEVOPS_SECRETS_TYPE_SERVICE_ACCOUNT_TOKEN)
                    .editOrNewMetadata()
                    .withName("name").withNamespace("ns").endMetadata().withData(data).build();
            assertNotNull(CredentialsUtils.upsertCredential(secret));
        }
    }

    @Test
    public void updateSourceCredentials() throws IOException {
        BuildConfig config = new BuildConfigBuilder().withNewMetadata()
                .withName("name").withNamespace("ns")
                .endMetadata().build();
        String credID = CredentialsUtils.updateSourceCredentials(config);
        assertNull(credID);

        assertNull(CredentialsUtils.getSourceCredentials(config));

        // exists secret
        final String secretName = j.getDevOpsInit().getSecretName();
        final String namespace = j.getDevOpsInit().getNamespace();
        config = new BuildConfigBuilder().withNewMetadata()
                .withName("name").withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withNewSource().withNewSourceSecret(secretName).endSource()
                .endSpec()
                .build();
        assertNotNull(CredentialsUtils.getSourceCredentials(config));

        credID = CredentialsUtils.updateSourceCredentials(config);
        assertNotNull(credID);
    }

    @Test
    public void deleteCredential() throws IOException {
        Secret secret = new SecretBuilder()
                .withNewMetadata().withNamespace("sdf").withName("name").endMetadata().build();
        CredentialsUtils.deleteCredential(secret);
    }
}
