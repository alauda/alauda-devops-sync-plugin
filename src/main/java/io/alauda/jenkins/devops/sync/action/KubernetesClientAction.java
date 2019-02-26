package io.alauda.jenkins.devops.sync.action;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.CronUtils;
import io.alauda.jenkins.devops.sync.watcher.ResourcesCache;
import io.alauda.kubernetes.client.Config;
import io.alauda.kubernetes.client.KubernetesClientException;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import org.acegisecurity.AccessDeniedException;
import org.apache.http.HttpRequest;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

@Extension
@Symbol("alauda")
@ExportedBean
public class KubernetesClientAction implements UnprotectedRootAction {
    private static final Logger logger = Logger.getLogger(KubernetesClientAction.class.getName());

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return "Kubernetes connect test";
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return "alauda";
    }

    public HttpResponse doConnectTest(@QueryParameter String server,
                                      @QueryParameter String credentialId,
                                      @QueryParameter boolean trustCerts) {
        Map<String, String> result = new HashMap<>();

        try {
            connectTest(server, credentialId, trustCerts);

            result.put("success", "true");
            result.put("message", "ok");
        } catch(KubernetesClientException e) {
            result.put("success", "false");
            result.put("message", e.getMessage());
        }

        return HttpResponses.okJSON(result);
    }

    @Exported
    public HttpResponse doBuildId() {
        Properties pro = new Properties();

        ClassLoader loader = KubernetesClientAction.class.getClassLoader();
        try(InputStream stream = loader.getResourceAsStream("debug.properties")) {
            if(stream != null) {
                pro.load(stream);

                return HttpResponses.okJSON(pro);
            }
        } catch (IOException e) {
            logger.severe(e.getMessage());
        }

        return HttpResponses.errorJSON("no debug file");
    }

    public HttpResponse doAllNamespaces() {
        try {
            Jenkins.getInstance().checkPermission(Jenkins.ADMINISTER);
        } catch (AccessDeniedException e) {
            return HttpResponses.errorJSON("No administer");
        }

        Set<String> allNamespaces = ResourcesCache.getInstance().getNamespaces();
        JSONArray array = new JSONArray();
        array.addAll(allNamespaces);
        return HttpResponses.okJSON(array);
    }

    /**
     * Do check cronTab text
     * @param cronText cron text
     * @return syntax check result, previous and next time
     */
    public HttpResponse doCronTabCheck(HttpRequest request, @QueryParameter String cronText) {
        Map<String, String> result = new HashMap<>();
        final Locale defaultLocale = Locale.getDefault();

        try {
            CronUtils cron = CronUtils.create(cronText, null);

            Calendar next = cron.next();
            Calendar previous = cron.previous();
            if(next != null) {
                result.put("next", String.valueOf(next.getTimeInMillis()));
            }

            if(previous != null) {
                result.put("previous", String.valueOf(previous.getTimeInMillis()));
            }

            Locale.setDefault(Locale.SIMPLIFIED_CHINESE);
            result.put("sanity_" + Locale.SIMPLIFIED_CHINESE, cron.checkSanity());

            Locale.setDefault(Locale.ENGLISH);
            result.put("sanity_" + Locale.ENGLISH, cron.checkSanity());
        } catch (ANTLRException e) {
            logger.warning(String.format("cron text syntax check error: %s.", e.getMessage()));
            result.put("error", e.getMessage());
        } finally {
            Locale.setDefault(defaultLocale);
        }

        return HttpResponses.okJSON(result);
    }

    public URL connectTest(String server, String credentialId, boolean trustCerts) {
        AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
        if (server != null && !server.isEmpty()) {
            configBuilder.withMasterUrl(server);
        }

        Config config = configBuilder.build();
        config.setTrustCerts(trustCerts);
        DefaultAlaudaDevOpsClient client = new DefaultAlaudaDevOpsClient(config);

        if(credentialId != null) {
            String token = CredentialsUtils.getToken(credentialId);
            if(token != null) {
                client.getConfiguration().setOauthToken(token);
            }
        }

        client.pods().inAnyNamespace().list();
        return client.getMasterUrl();
    }
}
