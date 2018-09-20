package io.alauda.jenkins.devops.sync;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AlaudaSyncSettingMonitorTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void basic() {
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        config.setEnabled(false);

        assertTrue(AlaudaSyncSettingMonitor.get(j.jenkins).isActivated());

        config.setEnabled(true);
        assertTrue(AlaudaSyncSettingMonitor.get(j.jenkins).isActivated());

        config.setJenkinsService("jenkins");
        assertFalse(AlaudaSyncSettingMonitor.get(j.jenkins).isActivated());
    }
}
