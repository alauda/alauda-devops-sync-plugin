package io.alauda.jenkins.devops.sync.util;

import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.PipelineConfig;
import org.junit.Rule;
import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PipelineConfigUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void test() {
        PipelineConfig config = j.getDevOpsInit().createPipelineConfig(j.getClient());

        assertTrue(PipelineConfigUtils.isSerialPolicy(config));
        assertFalse(PipelineConfigUtils.isParallel(config));
    }
}
