package io.alauda.jenkins.devops.sync;

import hudson.model.AdministrativeMonitor;

public class AlaudaSyncSettingMonitor extends AdministrativeMonitor {
    @Override
    public boolean isActivated() {
        String service = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
        return service == null || "".equals(service.trim());
    }
}
