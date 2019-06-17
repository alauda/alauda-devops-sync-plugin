package io.alauda.jenkins.devops.sync;

import io.alauda.jenkins.devops.sync.util.DevOpsInit;
import io.kubernetes.client.ApiClient;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;
import org.jvnet.hudson.test.JenkinsRule;

public class JenkinsK8sRule extends JenkinsRule {
    private boolean withK8s = true;
    private final boolean inK8s;
    private DevOpsInit devOpsInit;
    private ApiClient client;
    private int retryCount;

    public JenkinsK8sRule() {
        inK8s = "true".equals(System.getenv("IN_K8S"));
        String count = System.getenv("K8S_RETRY_COUNT");
        try {
            retryCount = Integer.parseInt(count);
        } catch (NumberFormatException e) {
            e.getMessage();
        }
        retryCount = Math.max(retryCount, 5);
        retryCount = Math.min(retryCount, 15);
    }

    @Override
    public void before() throws Throwable {
        super.before();

        if(withK8s) {
            devOpsInit = new DevOpsInit().init();
            client = devOpsInit.getClient();
            AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
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

        if(withK8s && !inK8s) {
            return new Statement() {
                @Override
                public void evaluate() throws Throwable {
                    System.out.println("skip: " + description.getMethodName());
                }
            };
        } else {
            return super.apply(base, description);
        }
    }

    public DevOpsInit getDevOpsInit() {
        return devOpsInit;
    }

    public AlaudaDevOpsClient getClient() {
        return client;
    }

    public int getRetryCount() {
        return retryCount;
    }
}
