package io.alauda.jenkins.devops.sync.credential;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.Extension;
import io.alauda.jenkins.plugins.credentials.SecretUtils;
import io.alauda.jenkins.plugins.credentials.convertor.CredentialsConversionException;
import io.alauda.jenkins.plugins.credentials.convertor.SecretToCredentialConverter;
import io.kubernetes.client.models.V1Secret;
import org.apache.commons.lang3.StringUtils;
import org.json.JSONException;
import org.json.JSONObject;

@Extension
public class DockerCredentialsConverter extends SecretToCredentialConverter {
  @Override
  public boolean canConvert(String s) {
    return ALAUDA_DEVOPS_SECRETS_TYPE_DOCKER.equals(s);
  }

  @Override
  public IdCredentials convert(V1Secret secret) throws CredentialsConversionException {
    SecretUtils.requireNonNull(
        secret.getData(), "kubernetes.io/dockerconfigjson definition contains no data");

    String dockerData =
        SecretUtils.getNonNullSecretData(
            secret,
            ALAUDA_DEVOPS_SECRETS_DATA_DOCKER,
            "kubernetes.io/dockerconfigjson credential is missing the .dockerconfigjson");
    JSONObject dockerAuthData;
    try {
      dockerAuthData = new JSONObject(dockerData);
    } catch (JSONException e) {
      throw new CredentialsConversionException(
          String.format(
              "Unable to convert kubernetes.io/dockerconfigjson secret %s/%s",
              secret.getMetadata().getNamespace(), secret.getMetadata().getName()),
          e);
    }
    JSONObject auths = dockerAuthData.getJSONObject("auths");

    if (auths == null) {
      throw new CredentialsConversionException(
          String.format(
              "Auths key in dockerconfigjson %s/%s is null",
              secret.getMetadata().getNamespace(), secret.getMetadata().getName()));
    }

    while (auths.keys().hasNext()) {
      String key = auths.keys().next();
      if (StringUtils.isEmpty(key)) {
        continue;
      }
      JSONObject authData = auths.getJSONObject(key);
      if (authData == null) {
        continue;
      }
      String username = authData.getString(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME);
      String password = authData.getString(ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD);
      if (StringUtils.isEmpty(username) && StringUtils.isEmpty(password)) {
        continue;
      }

      return new UsernamePasswordCredentialsImpl(
          CredentialsScope.GLOBAL,
          SecretUtils.getCredentialId(secret),
          SecretUtils.getCredentialDescription(secret),
          username,
          password);
    }
    return null;
  }
}
