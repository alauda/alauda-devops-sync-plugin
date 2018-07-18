package io.alauda.jenkins.devops.sync.action;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.kubernetes.client.Config;
import io.alauda.kubernetes.client.KubernetesClientException;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

@Extension
@Symbol("alauda")
@ExportedBean
public class KubernetesClientAction implements UnprotectedRootAction {
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
