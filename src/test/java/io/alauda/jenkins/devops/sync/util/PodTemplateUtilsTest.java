package io.alauda.jenkins.devops.sync.util;

import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertNotNull;

public class PodTemplateUtilsTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    @Ignore
    public void basic() throws InterruptedException {
        Thread.sleep(99999999);
        assertNotNull(PodTemplateUtils.getKubernetesCloud());
    }
}
