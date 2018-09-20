package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

import java.io.IOException;

@Extension
@Symbol("alaudaSyncSetting")
public class AlaudaSyncSettingMonitor extends AdministrativeMonitor {
    public static final String ID = "AlaudaSyncSetting";

    static AlaudaSyncSettingMonitor get(Jenkins j) {
        return (AlaudaSyncSettingMonitor) j.getAdministrativeMonitor(ID);
    }

    public AlaudaSyncSettingMonitor() {
        super(ID);
    }

    @Override
    public String getDisplayName() {
        return ID;
    }

    @Override
    public boolean isActivated() {
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        if(config == null || !config.isEnabled()) {
            return true;
        }

        String service = config.getJenkinsService();
        return service == null || "".equals(service.trim());
    }

    @RequirePOST
    public HttpResponse doAct(StaplerRequest req, StaplerResponse rsp) throws IOException {
        if (req.hasParameter("no")) {
            disable(true);
            return HttpResponses.redirectViaContextPath("/manage");
        } else {
            return HttpResponses.redirectViaContextPath("/configure");
        }
    }
}
