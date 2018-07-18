package io.alauda.jenkins.devops.sync.watcher;

import hudson.model.Job;
import hudson.model.TopLevelItem;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.DevOpsInit;
import io.alauda.jenkins.devops.sync.GlobalPluginConfiguration;
import io.alauda.jenkins.devops.sync.util.PipelineConfigUtils;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

import static org.junit.Assert.*;

public class PipelineConfigWatcherTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();
    private AlaudaDevOpsClient client;
    private DevOpsInit devOpsInit;

    @Before
    public void setup() throws IOException {
        devOpsInit = new DevOpsInit().init();
        client = devOpsInit.getClient();
        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
        config.setJenkinsService(devOpsInit.getJenkinsName());
        config.configChange();
    }

    @Test
    public void jobSync() throws Exception {
        Job jobItem = null;
        PipelineConfig config = devOpsInit.createPipelineConfig(client);
        String folderName = devOpsInit.getNamespace();
        String jobName = config.getMetadata().getName();

        int i = 0;
        while(i++ <= 10) {
            TopLevelItem folder = j.jenkins.getItem(folderName);
            if(folder != null) {
                jobItem = folder.getAllJobs().stream()
                        .filter(job -> ((Job) job).getName().equals(folderName + "-" + jobName))
                        .findFirst()
                        .orElse(null);
                if(jobItem != null) {
                    break;
                }
            }

            Thread.sleep(1000);
        }

        assertNotNull(jobItem);
        assertEquals(jobItem.getClass(), WorkflowJob.class);

        WorkflowJob workflowJob = (WorkflowJob) jobItem;

        assertEquals(workflowJob.isConcurrentBuild(), PipelineConfigUtils.isParallel(config));
        assertNotEquals(workflowJob.isConcurrentBuild(), PipelineConfigUtils.isSerialPolicy(config));
    }

    @After
    public void tearDown() throws IOException {
        devOpsInit.close();
    }
}
