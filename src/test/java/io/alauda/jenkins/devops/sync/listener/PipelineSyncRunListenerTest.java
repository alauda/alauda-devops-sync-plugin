package io.alauda.jenkins.devops.sync.listener;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class PipelineSyncRunListenerTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void constructor() {
        new PipelineSyncRunListener();
    }
}
