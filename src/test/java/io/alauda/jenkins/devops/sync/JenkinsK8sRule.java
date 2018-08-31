package io.alauda.jenkins.devops.sync;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.DevOpsInit;
import org.junit.runner.Description;
import org.junit.runners.model.FrameworkMethod;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;

public class JenkinsK8sRule extends JenkinsRule {
    private boolean withK8s = true;
    private DevOpsInit devOpsInit;
    private AlaudaDevOpsClient client;

    @Override
    public void before() throws Throwable {
        super.before();

        if(withK8s) {
            devOpsInit = new DevOpsInit().init();
            client = devOpsInit.getClient();
            GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
            config.setJenkinsService(devOpsInit.getJenkinsName());
            config.configChange();
        }
    }

    @Override
    public void after() throws Exception {
        super.after();

        if(withK8s) {
            devOpsInit.close();
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        if(description.getAnnotation(WithoutK8s.class) != null) {
            withK8s = false;
        }
        return super.apply(base, description);
    }

    @Override
    public Statement apply(Statement base, FrameworkMethod method, Object target) {
        return super.apply(base, method, target);
    }

    public DevOpsInit getDevOpsInit() {
        return devOpsInit;
    }

    public AlaudaDevOpsClient getClient() {
        return client;
    }
}
