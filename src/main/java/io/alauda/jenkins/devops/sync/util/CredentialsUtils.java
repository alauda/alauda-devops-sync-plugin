package io.alauda.jenkins.devops.sync.util;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import hudson.model.Fingerprint;
import hudson.model.TopLevelItem;
import hudson.remoting.Base64;
import hudson.security.ACL;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.core.InvalidSecretException;
import io.alauda.jenkins.devops.sync.credential.AlaudaToken;
import io.alauda.kubernetes.api.model.*;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.json.JSONObject;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixNull;
import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public abstract class CredentialsUtils {
    private final static Logger logger = Logger.getLogger(CredentialsUtils.class.getName());

    private CredentialsUtils(){}

//    public static synchronized Secret getSourceCredentials(BuildConfig buildConfig) {
//        if (buildConfig.getSpec() != null
//                && buildConfig.getSpec().getSource() != null
//                && buildConfig.getSpec().getSource().getSourceSecret() != null
//                && !buildConfig.getSpec().getSource().getSourceSecret()
//                        .getName().isEmpty()) {
//            Secret sourceSecret = AlaudaUtils.getAuthenticatedAlaudaClient()
//                    .secrets()
//                    .inNamespace(buildConfig.getMetadata().getNamespace())
//                    .withName(
//                            buildConfig.getSpec().getSource().getSourceSecret()
//                                    .getName()).get();
//            return sourceSecret;
//        }
//        return null;
//    }

  private static synchronized Secret getSourceCredentials(final PipelineConfig pipelineConfig) {
      PipelineConfigSpec spec = pipelineConfig.getSpec();
      if(spec == null) {
          return null;
      }

      PipelineSource source = spec.getSource();
      if(source == null) {
          return null;
      }

      SecretKeySetRef secret = source.getSecret();
      if(secret != null && StringUtils.isNotBlank(secret.getName())) {
          final String namespace = StringUtils.isBlank(secret.getNamespace())
                  ? pipelineConfig.getMetadata().getNamespace() : secret.getNamespace();

          return AlaudaUtils.getAuthenticatedAlaudaClient()
                  .secrets()
                  .inNamespace(namespace)
                  .withName(secret.getName()).get();
      }

      return null;
  }

//    public static synchronized String updateSourceCredentials(BuildConfig buildConfig) throws IOException {
//        Secret sourceSecret = getSourceCredentials(buildConfig);
//        String credID = null;
//        if (sourceSecret != null) {
//            credID = upsertCredential(sourceSecret, sourceSecret
//                    .getMetadata().getNamespace(), sourceSecret.getMetadata()
//                    .getName());
//            if (credID != null)
//                BuildConfigSecretToCredentialsMap.linkBCSecretToCredential(
//                    NamespaceName.create(buildConfig).toString(), credID);
//
//        } else {
//            // call delete and remove any credential that fits the
//            // project/bcname pattern
//            credID = BuildConfigSecretToCredentialsMap
//                    .unlinkBCSecretToCrendential(NamespaceName.create(
//                            buildConfig).toString());
//            if (credID != null)
//                deleteCredential(credID, NamespaceName.create(buildConfig),
//                        buildConfig.getMetadata().getResourceVersion());
//        }
//        return credID;
//    }

    /**
     * @param pipelineConfig
     * @return
     * @throws IOException
     * @throws InvalidSecretException
     */
    public static synchronized String updateSourceCredentials(final PipelineConfig pipelineConfig) throws IOException {
        final Secret sourceSecret = getSourceCredentials(pipelineConfig);
        final String credID;
        if (sourceSecret != null) {
            final ObjectMeta metadata = sourceSecret.getMetadata();
            final String namespace = metadata.getNamespace();
            final String name = metadata.getName();

            credID = upsertCredential(sourceSecret, namespace, name);
            if(credID == null) {
                return null;
            }

            PipelineConfigSecretToCredentialsMap.linkPCSecretToCredential(NamespaceName.create(pipelineConfig).toString(), credID);
        } else {
            // call delete and remove any credential that fits the
            // project/bcname pattern
            credID = PipelineConfigSecretToCredentialsMap.unlinkPCSecretToCrendential(NamespaceName.create(pipelineConfig).toString());
            if (credID != null) {
                deleteCredential(credID, NamespaceName.create(pipelineConfig), pipelineConfig.getMetadata().getResourceVersion());
            }
        }

        return credID;
    }

    public static synchronized void deleteSourceCredentials(PipelineConfig pipelineConfig) throws IOException {
        Secret sourceSecret = getSourceCredentials(pipelineConfig);
        if (sourceSecret != null) {
            // for a pc delete, if we are watching this secret, do not delete
            // credential until secret is actually deleted
            // We should only delete once the secret is deleted
            return;
        }
    }

    /**
     * Inserts or creates a Jenkins Credential for the given Secret
     * @param secret secret
     * @return id
     * @throws IOException connect error
     */
    public static synchronized String upsertCredential(Secret secret)
            throws IOException {
        if (secret != null) {
            ObjectMeta metadata = secret.getMetadata();
            if (metadata != null) {
                return upsertCredential(secret, metadata.getNamespace(),
                        metadata.getName());
            }
        }
        return null;
    }

    /**
     *
     * @param secret secret obj
     * @param namespace k8s namespace
     * @param secretName secret name
     * @return credential id
     * @throws IOException in case io exception
     * @throws InvalidSecretException if get a not support secret
     */
    @NotNull
    public static String upsertCredential(final Secret secret, final String namespace, final String secretName)
            throws IOException {
        final String id = secretName(namespace, secretName);
        if (secret != null) {
            Credentials credentials = secretToCredentials(secret);
            if (credentials == null) {
                throw new InvalidSecretException(secret.getKind());
            }

            Credentials existingCredentials = lookupCredentials(namespace, id);
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                CredentialsStore store = getStore(namespace);
                if(store == null) {
                    return null;
                }

                if (existingCredentials != null) {
                    store.updateCredentials(Domain.global(), existingCredentials, credentials);
                    logger.info("Updated credential " + id + " from Secret "
                            + NamespaceName.create(secret) + " with revision: "
                            + secret.getMetadata().getResourceVersion());
                } else {
                    store.addCredentials(Domain.global(), credentials);
                    logger.info("Created credential " + id + " from Secret "
                            + NamespaceName.create(secret) + " with revision: "
                            + secret.getMetadata().getResourceVersion());
                }
                store.save();
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        }

        return id;
    }
    
    private static void deleteCredential(String id, NamespaceName name,
            String resourceRevision) throws IOException {
        Credentials existingCred = lookupCredentials(name.getNamespace(), id);
        if (existingCred != null) {
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                Fingerprint fp = CredentialsProvider.getFingerprintOf(existingCred);
                if (fp != null && fp.getJobs().size() > 0) {
                    // per messages in credentials console, it is not a given,
                    // but
                    // it is possible for job refs to a credential to be
                    // tracked;
                    // if so, we will not prevent deletion, but at least note
                    // things
                    // for potential diagnostics
                    StringBuffer sb = new StringBuffer();
                    for (String job : fp.getJobs())
                        sb.append(job).append(" ");
                    logger.info("About to delete credential " + id
                            + "which is referenced by jobs: " + sb.toString());
                }
                CredentialsStore store = getStore(name.getNamespace());
                if(store == null) {
                    return;
                }

                store.removeCredentials(Domain.global(), existingCred);
                logger.info("Deleted credential " + id + " from Secret " + name
                        + " with revision: " + resourceRevision);
                store.save();
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        }
    }

    public static void deleteCredential(Secret secret) throws IOException {
        if (secret != null) {
            String id = secretName(secret.getMetadata().getNamespace(), secret
                    .getMetadata().getName());
            deleteCredential(id, NamespaceName.create(secret), secret
                    .getMetadata().getResourceVersion());
        }
    }

    // getCurrentToken returns the ServiceAccount token currently selected by
    // the user. A return value of empty string
    // implies no token is configured.
    public static String getCurrentToken() {
        if (AlaudaSyncGlobalConfiguration.get() == null) {
          logger.info("global plugin configuration is null");
          return "";
        }

        String credentialsId = AlaudaSyncGlobalConfiguration.get().getCredentialsId();
        if (credentialsId.equals("")) {
            return "";
        }

        String token = getToken(credentialsId);
        return token == null ? "" : token;
    }

    public static String getToken(String credentialId) {
        AlaudaToken token = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AlaudaToken.class,
                        Jenkins.getInstance(), ACL.SYSTEM,
                        Collections.<DomainRequirement> emptyList()),
                CredentialsMatchers.withId(credentialId));

        if (token != null) {
            return token.getToken();
        } else {
            return null;
        }
    }

    public static Credentials lookupCredentials(String namespace, String id) {
        final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
        try {
            return findCredentials(namespace, id);
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    public static Credentials findCredentials(String namespace, String id) {
        Jenkins jenkins = Jenkins.getInstance();
        List<Credentials> credentials;
        if(isGlobal(namespace)) {
            credentials = CredentialsProvider.lookupCredentials(Credentials.class,
                    jenkins, ACL.SYSTEM,
                    Collections.emptyList());
        } else{
            credentials = CredentialsProvider.lookupCredentials(Credentials.class,
                    jenkins.getItem(namespace), ACL.SYSTEM,
                    Collections.emptyList());
        }

        return CredentialsMatchers.firstOrNull(credentials, CredentialsMatchers.withId(id));
    }

    public static CredentialsStore getStore(String namespace) {
        Jenkins jenkins = Jenkins.getInstance();

        if(isGlobal(namespace)) {
            return CredentialsProvider
                    .lookupStores(jenkins).iterator()
                    .next();
        } else {
            TopLevelItem folder = jenkins.getItem(namespace);
            if(folder == null) {
                logger.warning(String.format("Can't find folder[%s], can't create credentials.", namespace));
                return null;
            }

            return CredentialsProvider
                    .lookupStores(folder).iterator()
                    .next();
        }
    }

    public static boolean isGlobal(String namespace) {
        if(namespace == null) {
            return true;
        }

        return (namespace.equals(AlaudaSyncGlobalConfiguration.get().getSharedNamespace()));
    }

    private static String secretName(String namespace, String name) {
        return namespace + "-" + name;
    }

    private static Credentials secretToCredentials(Secret secret) {
        String namespace = secret.getMetadata().getNamespace();
        String name = secret.getMetadata().getName();
        final Map<String, String> data = secret.getData();

        if (data == null) {
            logger.log(Level.WARNING, "An Kubernetes secret was marked for import, but it has no secret data.  No credential will be created.");
            return null;
        }

        //kubernetes.io/service-account-token
        final String secretName = secretName(namespace, name);
        switch (secret.getType()) {
            case ALAUDA_DEVOPS_SECRETS_TYPE_OPAQUE:
                String usernameData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME);
                String passwordData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD);
                if (isNotBlank(usernameData) && isNotBlank(passwordData)) {
                    return newUsernamePasswordCredentials(secretName, usernameData, passwordData);
                }
                String sshKeyData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY);
                if (isNotBlank(sshKeyData)) {
                    return newSSHUserCredential(secretName, data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME), sshKeyData);
                }

                logger.log(Level.WARNING, "Opaque secret either requires {0} and {1} fields for basic auth or {2} field for SSH key", new Object[]{ALAUDA_DEVOPS_SECRETS_DATA_USERNAME, ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD, ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY});
                return null;
            case ALAUDA_DEVOPS_SECRETS_TYPE_BASICAUTH:
                return newUsernamePasswordCredentials(secretName, data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME), data.get(ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD));
            case ALAUDA_DEVOPS_SECRETS_TYPE_SSH:
                return newSSHUserCredential(secretName, data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME), data.get(ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY));
            case ALAUDA_DEVOPS_SECRETS_TYPE_DOCKER:
                String dockerData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_DOCKER);
                return newDockerCredentials(secretName, dockerData);
            case ALAUDA_DEVOPS_SECRETS_TYPE_SERVICE_ACCOUNT_TOKEN:
                String token = secret.getData().get("token");
                return newTokenCredentials(secretName, token);
            case ALAUDA_DEVOPS_SECRETS_TYPE_OAUTH2:
                String accessTokenKey = secret.getData().get(ALAUDA_DEVOPS_SECRETS_DATA_ACCESSTOKENKEY);
                String clientSecret = secret.getData().get(ALAUDA_DEVOPS_SECRETS_DATA_ACCESSTOKEN);
                return newOauth2Credentials(secretName, accessTokenKey, clientSecret);
            default:
                logger.log(Level.WARNING, "Unknown secret type: " + secret.getType());
                return null;
        }
    }

    private static Credentials newSSHUserCredential(String secretName,
            String username, String sshKeyData) {
        if (secretName == null || secretName.length() == 0 ||
                sshKeyData == null || sshKeyData.length() == 0) {
            logger.log(Level.WARNING, "Invalid secret data, secretName: " +
                secretName + " sshKeyData is null: " + (sshKeyData == null) +
                " sshKeyData is empty: " + 
                (sshKeyData != null ? sshKeyData.length() == 0 : false));
            return null;
            
        }
        return new BasicSSHUserPrivateKey(CredentialsScope.GLOBAL, secretName,
                fixNull(username),
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource(
                        new String(Base64.decode(sshKeyData),
                                StandardCharsets.UTF_8)), null, secretName);
    }

    private static Credentials newUsernamePasswordCredentials(
            String secretName, String usernameData, String passwordData) {
        if (secretName == null || secretName.length() == 0 ||
                usernameData == null || usernameData.length()== 0 ||
                        passwordData == null || passwordData.length() == 0) {
            logger.log(Level.WARNING, "Invalid secret data, secretName: " +
                secretName + " usernameData is null: " + (usernameData == null)
                + " usernameData is empty: " + 
                (usernameData != null ? usernameData.length() == 0 : false) + 
                " passwordData is null: " + (passwordData == null) + 
                " passwordData is empty: " + 
                (passwordData != null ? passwordData.length() == 0 : false));
            return null;
            
        }
        return new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL,
                secretName, secretName, new String(Base64.decode(usernameData),
                        StandardCharsets.UTF_8), new String(
                        Base64.decode(passwordData), StandardCharsets.UTF_8));
    }

    /**
     * @param secretName secret name
     * @param token should encoded by base64
     * @return credential
     */
    public static Credentials newTokenCredentials(String secretName, String token) {
        token = new String(Base64.decode(token), StandardCharsets.UTF_8);

        hudson.util.Secret secret = hudson.util.Secret.fromString(token);
        return new AlaudaToken(CredentialsScope.GLOBAL, secretName, null, secret);
    }

    private static Credentials newOauth2Credentials(String secretName, String accessTokenKey, String clientSecret) {
        return newUsernamePasswordCredentials(secretName, accessTokenKey, clientSecret);
    }

    private static Credentials newDockerCredentials(String secretName, String dockerData) {
      if (secretName == null || secretName.length() == 0 || dockerData == null || dockerData.length() == 0) {
        logger.log(Level.WARNING, "Invalid secret data, secretName: " +
          secretName + " dockerData is null: " + (dockerData == null));
        return null;
      }
      // decoding
      dockerData = new String(Base64.decode(dockerData), StandardCharsets.UTF_8);
      try {
        JSONObject obj = new JSONObject(dockerData);
        JSONObject auths = obj.getJSONObject("auths");
        if (auths == null) {
          throw new Exception("Auths key in dockerconfig is null: "+dockerData);
        }
        while (auths.keys().hasNext()) {
          String key = auths.keys().next();
          if (key == null || key.length() == 0) {
            continue;
          }
          JSONObject authData = auths.getJSONObject(key);
          if (authData == null) {
            continue;
          }
          String username = authData.getString("username");
          String password = authData.getString("password");
          logger.info(String.format("Username: %s password: ***", username));
          if (StringUtils.isEmpty(username) || StringUtils.isEmpty(password)) {
            logger.log(Level.WARNING, "Invalid docker data, secretName: " +
              secretName + " username is empty? "+StringUtils.isEmpty(username)+" password is empty?" + StringUtils.isEmpty(password));
          }
          return new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, secretName, secretName, username, password);
        }
        /*
        {"auths":{"index.alauda.cn":{"username":"alaudak8s","password":"Y1Pd4StTY4CF","email":"devs@alauda.io","auth":"YWxhdWRhazhzOlkxUGQ0U3RUWTRDRg=="}}}
         */
      } catch (Exception e) {
        logger.log(Level.WARNING, "Invalid docker data, secretName: " +
          secretName + " dockerData is " + dockerData+ " e:"+e, e);
        return null;
      }
      return null;
    }

    /**
     * Does our configuration have credentials?
     * 
     * @return true if found.
     */
    public static boolean hasCredentials() {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return false;
        }

        return !StringUtils.isEmpty(client.getConfiguration().getOauthToken());
    }

}
