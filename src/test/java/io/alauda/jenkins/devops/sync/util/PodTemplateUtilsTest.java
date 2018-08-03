package io.alauda.jenkins.devops.sync.util;

import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class PodTemplateUtilsTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void basic() {
        assertNull(PodTemplateUtils.getKubernetesCloud());

        assertFalse(PodTemplateUtils.hasPodTemplate("hello"));
        assertFalse(PodTemplateUtils.hasPodTemplate(null));
        assertNull(PodTemplateUtils.getPodTemplates());
        assertFalse(PodTemplateUtils.addPodTemplate(null));
        assertFalse(PodTemplateUtils.removePodTemplate(null));
    }

    @Test
    public void podTemplateInit() {
        final String name = "name";
        final String image = "image";
        final String label = "label";

        PodTemplate pod = PodTemplateUtils.podTemplateInit(name, image, label);
        assertNotNull(pod);
        assertEquals(name, pod.getName());
        assertEquals(image, pod.getImage());
        assertEquals(label, pod.getLabel());
        assertNotNull(pod.getCommand());
        assertNotNull(pod.getArgs());
        assertNotNull(pod.getRemoteFs());
    }
}
