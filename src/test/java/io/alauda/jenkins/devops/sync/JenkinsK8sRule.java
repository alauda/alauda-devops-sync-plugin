package io.alauda.jenkins.devops.sync;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.DevOpsInit;
import org.jvnet.hudson.test.JenkinsRule;

public class JenkinsK8sRule extends JenkinsRule {
    private DevOpsInit devOpsInit;
    private AlaudaDevOpsClient client;

    @Override
    public void before() throws Throwable {
        super.before();

        devOpsInit = new DevOpsInit().init();
        client = devOpsInit.getClient();
        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
        config.setJenkinsService(devOpsInit.getJenkinsName());
        config.configChange();
    }

    @Override
    public void after() throws Exception {
        super.after();

        devOpsInit.close();
    }

    public DevOpsInit getDevOpsInit() {
        return devOpsInit;
    }

    public AlaudaDevOpsClient getClient() {
        return client;
    }
}
