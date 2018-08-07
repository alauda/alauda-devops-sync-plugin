package io.alauda.jenkins.devops.sync.util;

import io.alauda.jenkins.devops.sync.JenkinsK8sRule;
import io.alauda.kubernetes.api.model.Pod;
import io.alauda.kubernetes.api.model.ServiceAccount;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class PodTemplateUtilsTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void basic() {
        assertNull(PodTemplateUtils.getKubernetesCloud());

        assertFalse(PodTemplateUtils.hasPodTemplate("hello"));
        assertFalse(PodTemplateUtils.hasPodTemplate(null));
        assertNotNull(PodTemplateUtils.getPodTemplates());
        assertFalse(PodTemplateUtils.addPodTemplate(null));
        assertFalse(PodTemplateUtils.removePodTemplate(null));
        assertFalse(PodTemplateUtils.removePodTemplate("  "));
        assertFalse(PodTemplateUtils.removePodTemplate("hello"));
    }

    @Test
    public void podTemplateInit() {
        final String name = "name";
        final String image = "image";
        final String label = "label";

        ServiceAccount account = j.getDevOpsInit().createServiceAccounts(j.getClient());
        assertNotNull(account);
        final String accountName = account.getMetadata().getName();

        Pod pod = j.getDevOpsInit().createPod(j.getClient(), accountName);
        assertNotNull(pod);

        System.setProperty("HOSTNAME", pod.getMetadata().getName());

        PodTemplate podTemplate = PodTemplateUtils.podTemplateInit(name, image, label);
        assertNotNull(podTemplate);
        assertEquals(name, podTemplate.getName());
        assertEquals(image, podTemplate.getImage());
        assertEquals(label, podTemplate.getLabel());
        assertNotNull(podTemplate.getCommand());
        assertNotNull(podTemplate.getArgs());
        assertNotNull(podTemplate.getRemoteFs());

        PodTemplateUtils.addPodTemplate(podTemplate);
    }
}
