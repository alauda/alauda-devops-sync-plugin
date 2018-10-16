package io.alauda.jenkins.devops.sync.action;

import antlr.ANTLRException;
import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.scheduler.CronTab;
import hudson.scheduler.CronTabList;
import hudson.scheduler.Hash;
import hudson.scheduler.Messages;
import hudson.util.HttpResponses;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.CronUtils;
import io.alauda.kubernetes.client.Config;
import io.alauda.kubernetes.client.KubernetesClientException;
import org.apache.tools.ant.taskdefs.condition.Http;
import org.jenkinsci.Symbol;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;
import java.util.logging.Level;
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
                                      @QueryParameter String credentialId) {
        Map<String, String> result = new HashMap<>();

        try {
            connectTest(server, credentialId);

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

    /**
     * Do check cronTab text
     * @param cronText cron text
     * @return syntax check result, previous and next time
     */
    public HttpResponse doCronTabCheck(@QueryParameter String cronText) {
        Map<String, String> result = new HashMap<>();

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

            result.put("sanity", cron.checkSanity());
        } catch (ANTLRException e) {
            logger.warning(String.format("cron text syntax check error: %s.", e.getMessage()));
            result.put("error", e.getMessage());
        }

        return HttpResponses.okJSON(result);
    }

    public URL connectTest(String server, String credentialId) {
        AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
        if (server != null && !server.isEmpty()) {
            configBuilder.withMasterUrl(server);
        }

        Config config = configBuilder.build();
        DefaultAlaudaDevOpsClient client = new DefaultAlaudaDevOpsClient(config);

        if(credentialId != null) {
            String token = CredentialsUtils.getToken(credentialId);
            if(token != null) {
                client.getConfiguration().setOauthToken(token);
            }
        }

        client.namespaces().list();
        return client.getMasterUrl();
    }
}
