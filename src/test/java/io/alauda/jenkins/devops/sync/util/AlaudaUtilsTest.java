package io.alauda.jenkins.devops.sync.util;

import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.*;

public class AlaudaUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void isPipelineStrategyPipelineConfig() {
        V1alpha1PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        assertNotNull(config);

        assertTrue(AlaudaUtils.isPipelineStrategyPipelineConfig(config));
        assertFalse(AlaudaUtils.isPipelineStrategyPipelineConfig(null));
    }

    @Test
    public void jenkinsJobName() {
        V1alpha1PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());
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
