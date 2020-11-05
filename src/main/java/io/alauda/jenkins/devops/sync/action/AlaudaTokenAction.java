package io.alauda.jenkins.devops.sync.action;

import hudson.Extension;
import hudson.model.Api;
import hudson.model.UnprotectedRootAction;
import hudson.model.User;
import hudson.util.HttpResponses;
import io.alauda.jenkins.devops.support.KubernetesCluster;
import io.alauda.jenkins.devops.support.KubernetesClusterConfiguration;
import io.alauda.jenkins.devops.support.client.Clients;
import io.alauda.jenkins.devops.support.exception.KubernetesClientException;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.apis.CoreV1Api;
import io.kubernetes.client.util.credentials.AccessTokenAuthentication;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.CheckForNull;
import jenkins.security.ApiTokenProperty;
import jenkins.security.apitoken.ApiTokenStore;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * This action provides a API for getting the Jenkins token. In order to make sure you have the
 * access to get it, you need to pass the k8s cluster token.
 */
@Extension
@Symbol("alaudaToken")
@ExportedBean
public class AlaudaTokenAction implements UnprotectedRootAction {
  private static final Logger logger = LoggerFactory.getLogger(AlaudaTokenAction.class);

  public Api getApi() {
    return new Api(this);
  }

  @CheckForNull
  @Override
  public String getIconFileName() {
    return null;
  }

  @CheckForNull
  @Override
  public String getDisplayName() {
    return null;
  }

  @CheckForNull
  @Override
  public String getUrlName() {
    return "alaudaToken";
  }

  @Exported
  public HttpResponse doGenerate(StaplerRequest req, StaplerResponse rsp) {
    if (!authCheck(req.getParameter("token"))) {
      return HttpResponses.errorJSON("invalid token of Jenkins k8s cluster");
    }

    User user = User.getById("admin", true);
    if (user == null) {
      return HttpResponses.errorJSON("cannot found user admin");
    }
    ApiTokenProperty apiTokenPro = user.getProperty(ApiTokenProperty.class);

    final String targetTokenName =
        String.format(
            "Alauda token created on %s",
            DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(ZonedDateTime.now()));
    ApiTokenStore tokenStore = getTokenStore(apiTokenPro);
    if (tokenStore == null) {
      return HttpResponses.errorJSON("cannot found api token store");
    }

    Map<String, String> data = new HashMap<>();
    ApiTokenStore.TokenUuidAndPlainValue tokenVal = tokenStore.generateNewToken(targetTokenName);
    data.put("token", tokenVal.plainValue);

    return HttpResponses.okJSON(data);
  }

  private boolean authCheck(String token) {
    if (token == null || "".equals(token.trim())) {
      return false;
    }
    KubernetesCluster cluster = KubernetesClusterConfiguration.get().getCluster();

    ApiClient apiClient;
    try {
      apiClient = Clients.createClientFromCluster(cluster);
      new AccessTokenAuthentication(token).provide(apiClient);
    } catch (KubernetesClientException e) {
      logger.warn(
          "failed when create client for verifying k8s cluster purpose which cluste is "
              + cluster.getMasterUrl(),
          e);
      return false;
    }

    CoreV1Api api = new CoreV1Api(apiClient);
    try {
      api.listNamespace(null, null, null, null, null, null, null, null);
    } catch (ApiException e) {
      logger.warn("failed when verifying k8s cluster with " + cluster.getMasterUrl(), e);
      return false;
    }

    return true;
  }

  /**
   * Get the apiTokenStore by this way due to the NoExternalUse restriction
   *
   * @return ApiTokenStore
   */
  private ApiTokenStore getTokenStore(ApiTokenProperty apiTokenPro) {
    try {
      Method getTokenListMethod = apiTokenPro.getClass().getMethod("getTokenStore");
      return (ApiTokenStore) getTokenListMethod.invoke(apiTokenPro);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      logger.error("cannot get ApiTokenStore", e);
    }
    return null;
  }
}
