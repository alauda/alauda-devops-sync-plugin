package io.alauda.jenkins.devops.sync.util;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.*;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import com.iwombat.util.StringUtil;
import hudson.model.Fingerprint;
import hudson.remoting.Base64;
import hudson.security.ACL;
import io.alauda.devops.api.model.BuildConfig;
import io.alauda.jenkins.devops.sync.*;
import io.alauda.jenkins.devops.sync.credential.AlaudaToken;
import io.alauda.kubernetes.api.model.*;
import jenkins.model.Jenkins;

import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.json.*;

import static hudson.Util.fixNull;
import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static org.apache.commons.lang.StringUtils.isNotBlank;

public class CredentialsUtils {

    private final static Logger logger = Logger
            .getLogger(CredentialsUtils.class.getName());

    public static synchronized Secret getSourceCredentials(
            BuildConfig buildConfig) {
        if (buildConfig.getSpec() != null
                && buildConfig.getSpec().getSource() != null
                && buildConfig.getSpec().getSource().getSourceSecret() != null
                && !buildConfig.getSpec().getSource().getSourceSecret()
                        .getName().isEmpty()) {
            Secret sourceSecret = AlaudaUtils.getAuthenticatedAlaudaClient()
                    .secrets()
                    .inNamespace(buildConfig.getMetadata().getNamespace())
                    .withName(
                            buildConfig.getSpec().getSource().getSourceSecret()
                                    .getName()).get();
            return sourceSecret;
        }
        return null;
    }

  private static synchronized Secret getSourceCredentials(PipelineConfig pipelineConfig) {
      PipelineConfigSpec spec = pipelineConfig.getSpec();
      if(spec == null) {
          return null;
      }

      PipelineSource source = spec.getSource();
      if(source == null) {
          return null;
      }

      LocalObjectReference secret = source.getSecret();
      if(secret != null && StringUtils.isNotBlank(secret.getName())) {
          return AlaudaUtils.getAuthenticatedAlaudaClient()
                  .secrets()
                  .inNamespace(pipelineConfig.getMetadata().getNamespace())
                  .withName(secret.getName()).get();
      }

      return null;
  }

    public static synchronized String updateSourceCredentials(
            BuildConfig buildConfig) throws IOException {
        Secret sourceSecret = getSourceCredentials(buildConfig);
        String credID = null;
        if (sourceSecret != null) {
            credID = upsertCredential(sourceSecret, sourceSecret
                    .getMetadata().getNamespace(), sourceSecret.getMetadata()
                    .getName());
            if (credID != null)
                BuildConfigSecretToCredentialsMap.linkBCSecretToCredential(
                    NamespaceName.create(buildConfig).toString(), credID);

        } else {
            // call delete and remove any credential that fits the
            // project/bcname pattern
            credID = BuildConfigSecretToCredentialsMap
                    .unlinkBCSecretToCrendential(NamespaceName.create(
                            buildConfig).toString());
            if (credID != null)
                deleteCredential(credID, NamespaceName.create(buildConfig),
                        buildConfig.getMetadata().getResourceVersion());
        }
        return credID;
    }

  public static synchronized String updateSourceCredentials(PipelineConfig pipelineConfig) throws IOException {
    Secret sourceSecret = getSourceCredentials(pipelineConfig);
    String credID = null;
    if (sourceSecret != null) {
      credID = upsertCredential(sourceSecret, sourceSecret
        .getMetadata().getNamespace(), sourceSecret.getMetadata()
        .getName());
      if (credID != null)

        PipelineConfigSecretToCredentialsMap.linkPCSecretToCredential(
          NamespaceName.create(pipelineConfig).toString(), credID);

    } else {
      // call delete and remove any credential that fits the
      // project/bcname pattern
      credID = PipelineConfigSecretToCredentialsMap
        .unlinkPCSecretToCrendential(NamespaceName.create(
          pipelineConfig).toString());
      if (credID != null)
        deleteCredential(credID, NamespaceName.create(pipelineConfig),
          pipelineConfig.getMetadata().getResourceVersion());
    }
    return credID;
  }

  public static synchronized void deleteSourceCredentials(
    PipelineConfig pipelineConfig) throws IOException {
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

    private static String upsertCredential(Secret secret, String namespace,
            String secretName) throws IOException {
        String id = null;
        if (secret != null) {
            Credentials creds = secretToCredentials(secret);
            if (creds == null)
                return null;
            id = secretName(namespace, secretName);
            Credentials existingCreds = lookupCredentials(id);
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                CredentialsStore s = CredentialsProvider
                        .lookupStores(Jenkins.getActiveInstance()).iterator()
                        .next();
                if (existingCreds != null) {
                    s.updateCredentials(Domain.global(), existingCreds, creds);
                    logger.info("Updated credential " + id + " from Secret "
                            + NamespaceName.create(secret) + " with revision: "
                            + secret.getMetadata().getResourceVersion());
                } else {
                    s.addCredentials(Domain.global(), creds);
                    logger.info("Created credential " + id + " from Secret "
                            + NamespaceName.create(secret) + " with revision: "
                            + secret.getMetadata().getResourceVersion());
                }
                s.save();
            } finally {
                SecurityContextHolder.setContext(previousContext);
            }
        }
        return id;
    }
    
    private static void deleteCredential(String id, NamespaceName name,
            String resourceRevision) throws IOException {
        Credentials existingCred = lookupCredentials(id);
        if (existingCred != null) {
            final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
            try {
                Fingerprint fp = CredentialsProvider
                        .getFingerprintOf(existingCred);
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
                CredentialsStore s = CredentialsProvider
                        .lookupStores(Jenkins.getActiveInstance()).iterator()
                        .next();
                s.removeCredentials(Domain.global(), existingCred);
                logger.info("Deleted credential " + id + " from Secret " + name
                        + " with revision: " + resourceRevision);
                s.save();
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
        if (GlobalPluginConfiguration.get() == null) {
          logger.info("global plugin configuration is null");
          return "";
        }

        String credentialsId = GlobalPluginConfiguration.get().getCredentialsId();
        if (credentialsId.equals("")) {
            return "";
        }

        String token = getToken(credentialsId);
        return token == null ? "" : token;
    }

    public static String getToken(String credentialId) {
        AlaudaToken token = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(AlaudaToken.class,
                        Jenkins.getActiveInstance(), ACL.SYSTEM,
                        Collections.<DomainRequirement> emptyList()),
                CredentialsMatchers.withId(credentialId));

        if (token != null) {
            return token.getToken();
        } else {
            return null;
        }
    }

    private static Credentials lookupCredentials(String id) {
        return CredentialsMatchers.firstOrNull(CredentialsProvider
                .lookupCredentials(Credentials.class,
                        Jenkins.getActiveInstance(), ACL.SYSTEM,
                        Collections.<DomainRequirement> emptyList()),
                CredentialsMatchers.withId(id));
    }

    private static String secretName(String namespace, String name) {
        return namespace + "-" + name;
    }

    private static Credentials secretToCredentials(Secret secret) {
        String namespace = secret.getMetadata().getNamespace();
        String name = secret.getMetadata().getName();
        final Map<String, String> data = secret.getData();

        if (data == null) {
            logger.log(
                    Level.WARNING,
                    "An Kubernetes secret was marked for import, but it has no secret data.  No credential will be created.");
            return null;
        }

        //kubernetes.io/service-account-token
        final String secretName = secretName(namespace, name);
        switch (secret.getType()) {
        case ALAUDA_DEVOPS_SECRETS_TYPE_OPAQUE:
            String usernameData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME);
            String passwordData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD);
            if (isNotBlank(usernameData) && isNotBlank(passwordData)) {
                return newUsernamePasswordCredentials(secretName, usernameData,
                        passwordData);
            }
            String sshKeyData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY);
            if (isNotBlank(sshKeyData)) {
                return newSSHUserCredential(secretName,
                        data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME), sshKeyData);
            }

            logger.log(
                    Level.WARNING,
                    "Opaque secret either requires {0} and {1} fields for basic auth or {2} field for SSH key",
                    new Object[] {ALAUDA_DEVOPS_SECRETS_DATA_USERNAME,
                      ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD,
                      ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY});
            return null;
        case ALAUDA_DEVOPS_SECRETS_TYPE_BASICAUTH:
            return newUsernamePasswordCredentials(secretName,
                    data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME),
                    data.get(ALAUDA_DEVOPS_SECRETS_DATA_PASSWORD));
        case ALAUDA_DEVOPS_SECRETS_TYPE_SSH:
            return newSSHUserCredential(secretName,
                    data.get(ALAUDA_DEVOPS_SECRETS_DATA_USERNAME),
                    data.get(ALAUDA_DEVOPS_SECRETS_DATA_SSHPRIVATEKEY));
          case ALAUDA_DEVOPS_SECRETS_TYPE_DOCKER:
            String dockerData = data.get(ALAUDA_DEVOPS_SECRETS_DATA_DOCKER);
            return newDockerCredentials(secretName, dockerData);
            case ALAUDA_DEVOPS_SECRETS_TYPE_SERVICE_ACCOUNT_TOKEN:
                String token = secret.getData().get("token");
                return newTokenCredentials(secretName, token);
        default:
            logger.log(Level.WARNING,
                    "Unknown secret type: " + secret.getType());
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

    public static Credentials newTokenCredentials(String secretName, String token) {
        token = new String(Base64.decode(token), StandardCharsets.UTF_8);

        hudson.util.Secret secret = hudson.util.Secret.fromString(token);
        return new AlaudaToken(CredentialsScope.GLOBAL, secretName, null, secret);
//        return new OpenShiftTokenCredentialImpl(CredentialsScope.GLOBAL, secretName(namespace, secretName), null, secret);
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
          logger.info("Username: "+username+" password:"+password);
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
        return !StringUtils.isEmpty(AlaudaUtils.getAuthenticatedAlaudaClient()
                .getConfiguration().getOauthToken());
    }

}
