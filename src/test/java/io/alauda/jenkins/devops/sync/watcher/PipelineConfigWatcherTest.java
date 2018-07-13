package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.TopLevelItem;
import hudson.slaves.CommandLauncher;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockFolder;

import java.io.IOException;

import static junit.framework.TestCase.assertNotNull;

public class PipelineConfigWatcherTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void test() throws Exception {
        AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
        Config config = configBuilder.build();
        AlaudaDevOpsClient client = new DefaultAlaudaDevOpsClient(config);

        JenkinsBindingList list = client.jenkinsBindings().inAnyNamespace().list();
        assertNotNull(list);
        assertNotNull(list.getItems());

        PipelineStrategyJenkins jenkins = new PipelineStrategyJenkinsBuilder()
                .withJenkinsfile("")
                .build();

        PipelineStrategy strategy = new PipelineStrategyBuilder()
                .withJenkins(jenkins)
                .build();

        client.pipelineConfigs().inNamespace("test")
                .createNew()
                .withNewSpec()
                .withStrategy(strategy)
                .endSpec()
                .done();

        while(true){
            for(TopLevelItem item : j.jenkins.getItems()) {
                System.out.println(item.getName());
            }

            Thread.sleep(2000);
        }
    }
}
