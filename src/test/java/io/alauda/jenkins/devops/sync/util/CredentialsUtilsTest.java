package io.alauda.jenkins.devops.sync.util;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertNull;

public class CredentialsUtilsTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void getToken() {
        // fake credential id test
        assertNull(CredentialsUtils.getToken("fake"));
    }
}
