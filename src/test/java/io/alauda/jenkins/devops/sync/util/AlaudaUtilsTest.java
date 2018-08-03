package io.alauda.jenkins.devops.sync.util;

import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class AlaudaUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void isPipelineStrategyPipelineConfig() {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        assertNotNull(config);

        assertTrue(AlaudaUtils.isPipelineStrategyPipelineConfig(config));
    }

    @Test
    public void jenkinsJobName() {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        assertNotNull(config);

        String jobName = AlaudaUtils.jenkinsJobName(config);
        assertNotNull(jobName);

        jobName = AlaudaUtils.jenkinsJobName(config.getMetadata().getNamespace(),
                config.getMetadata().getName());
        assertNotNull(jobName);

        jobName = AlaudaUtils.jenkinsJobFullName(config);
        assertNotNull(jobName);
    }
}
