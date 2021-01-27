package io.alauda.jenkins.devops.sync.credential;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.common.IdCredentials;
import hudson.Extension;
import io.alauda.jenkins.plugins.credentials.SecretUtils;
import io.alauda.jenkins.plugins.credentials.convertor.CredentialsConversionException;
import io.alauda.jenkins.plugins.credentials.convertor.SecretToCredentialConverter;
import io.kubernetes.client.openapi.models.V1Secret;
import java.util.Optional;

@Extension
public class SSHAuthCredentialsConverter extends SecretToCredentialConverter {
  @Override
  public boolean canConvert(String s) {
    return ALAUDA_DEVOPS_SECRETS_TYPE_SSH.equals(s);
  }

  @Override
  public IdCredentials convert(V1Secret secret) throws CredentialsConversionException {
    SecretUtils.requireNonNull(
        secret.getData(), "kubernetes.io/ssh-auth definition contains no data");

    String privateKey =
        SecretUtils.getNonNullSecretData(
            secret,
            ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY,
            "kubernetes.io/ssh-auth credential is missing the ssh-privatekey");

    Optional<String> optUsername =
        SecretUtils.getOptionalSecretData(
            secret,
            ALAUDA_DEVOPS_SECRETS_DATA_USERNAME,
            "kubernetes.io/ssh-auth credential is missing the username");
    Optional<String> optPassphrase =
        SecretUtils.getOptionalSecretData(
            secret,
            ALAUDA_DEVOPS_SECRETS_DATA_PASSPHRASE,
            "basicSSHUserPrivateKey credential: failed to retrieve passphrase, assuming private key has an empty passphrase");
    String passphrase = null;

    if (optPassphrase.isPresent()) {
      passphrase =
          SecretUtils.requireNonNull(
              optPassphrase.get(),
              "basicSSHUserPrivateKey credential has an invalid passphrase (must be base64 encoded UTF-8)");
    }

    return new BasicSSHUserPrivateKey(
        CredentialsScope.GLOBAL,
        SecretUtils.getCredentialId(secret),
        optUsername.orElse(""),
        new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(privateKey),
        passphrase,
        SecretUtils.getCredentialDescription(secret));
  }
}
