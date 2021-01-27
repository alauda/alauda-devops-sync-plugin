package io.alauda.jenkins.devops.sync.credential;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.Extension;
import io.alauda.jenkins.plugins.credentials.SecretUtils;
import io.alauda.jenkins.plugins.credentials.convertor.CredentialsConversionException;
import io.alauda.jenkins.plugins.credentials.convertor.SecretToCredentialConverter;
import io.kubernetes.client.openapi.models.V1Secret;

@Extension
public class OAuth2CredentialsConverter extends SecretToCredentialConverter {
  @Override
  public boolean canConvert(String s) {
    return ALAUDA_DEVOPS_SECRETS_TYPE_OAUTH2.equals(s);
  }

  @Override
  public IdCredentials convert(V1Secret secret) throws CredentialsConversionException {
    SecretUtils.requireNonNull(
        secret.getData(), "devops.alauda.io/oauth2 definition contains no data");

    String accessTokenKey =
        SecretUtils.getNonNullSecretData(
            secret,
            ALAUDA_DEVOPS_SECRETS_DATA_ACCESSTOKENKEY,
            "devops.alauda.io/oauth2 is missing the accessTokenKey");
    String clientSecret =
        SecretUtils.getNonNullSecretData(
            secret,
            ALAUDA_DEVOPS_SECRETS_DATA_ACCESSTOKEN,
            "devops.alauda.io/oauth2 is missing the accessToken");

    return new UsernamePasswordCredentialsImpl(
        CredentialsScope.GLOBAL,
        SecretUtils.getCredentialId(secret),
        SecretUtils.getCredentialDescription(secret),
        accessTokenKey,
        clientSecret);
  }
}
