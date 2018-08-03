package io.alauda.jenkins.devops.sync.util;

import hudson.slaves.Cloud;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.kubernetes.api.model.Pod;
import jenkins.model.Jenkins;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.getAuthenticatedAlaudaClient;

public abstract class PodTemplateUtils {
    private static final Logger LOGGER = Logger.getLogger(PodTemplateUtils.class.getName());

    private PodTemplateUtils() {}

    public static boolean removePodTemplate(PodTemplate podTemplate) {
        if(podTemplate == null) {
            return false;
        }

        KubernetesCloud kubeCloud = PodTemplateUtils.getKubernetesCloud();
        if (kubeCloud != null) {
            LOGGER.info(() -> "Removing PodTemplate: " + podTemplate.getName());
            // NOTE - PodTemplate does not currently override hashCode, equals,
            // so
            // the KubernetsCloud.removeTemplate currently is broken;
            // kubeCloud.removeTemplate(podTemplate);
            List<PodTemplate> list = kubeCloud.getTemplates();
            Iterator<PodTemplate> iter = list.iterator();
            while (iter.hasNext()) {
                PodTemplate pt = iter.next();
                if (pt.getName().equals(podTemplate.getName())) {
                    iter.remove();
                }
            }
            // now set new list back into cloud
            kubeCloud.setTemplates(list);
            try {
                // pedantic mvn:findbugs
                Jenkins jenkins = Jenkins.getInstance();
                jenkins.save();

                return true;
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "removePodTemplate", e);
            } finally {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("PodTemplates now:");
                    for (PodTemplate pt : kubeCloud.getTemplates()) {
                        LOGGER.fine(pt.getName());
                    }
                }
            }
        }

        return false;
    }

    public static List<PodTemplate> getPodTemplates() {
        KubernetesCloud kubeCloud = PodTemplateUtils.getKubernetesCloud();
        if (kubeCloud != null) {
            // create copy of list for more flexiblity in loops
            return new ArrayList<>(kubeCloud.getTemplates());
        } else {
            return null;
        }
    }

    public static boolean hasPodTemplate(String name) {
        if (name == null){
            return false;
        }

        KubernetesCloud kubeCloud = PodTemplateUtils.getKubernetesCloud();
        if (kubeCloud != null) {
            List<PodTemplate> list = kubeCloud.getTemplates();
            for (PodTemplate pod : list) {
                if (name.equals(pod.getName()))
                    return true;
            }
        }
        return false;
    }

    public static boolean addPodTemplate(PodTemplate podTemplate) {
        if(podTemplate == null) {
            return false;
        }

        // clear out existing template with same name; k8s plugin maintains
        // list, not map
        removePodTemplate(podTemplate);

        KubernetesCloud kubeCloud = PodTemplateUtils.getKubernetesCloud();
        if (kubeCloud != null) {
            LOGGER.info(() -> "Adding PodTemplate: " + podTemplate.getName());
            kubeCloud.addTemplate(podTemplate);
            try {
                // pedantic mvn:findbugs
                Jenkins.getInstance().save();
                return true;
            } catch (IOException e) {
                LOGGER.log(Level.SEVERE, "addPodTemplate", e);
            }
        }

        return false;
    }


    public static KubernetesCloud getKubernetesCloud() {
        // pedantic mvn:findbugs
        Jenkins jenkins = Jenkins.getInstance();
        Cloud openShiftCloud = jenkins.getCloud("openshift");
        if (openShiftCloud instanceof KubernetesCloud) {
            return (KubernetesCloud) openShiftCloud;
        }

        return null;
    }

    public static PodTemplate podTemplateInit(String name, String image, String label) {
        PodTemplate podTemplate = new PodTemplate(image, new ArrayList<PodVolumes.PodVolume>());
        // with the above ctor guarnateed to have 1 container
        // also still force our image as the special case "jnlp" container for
        // the KubernetesSlave;
        // attempts to use the "jenkinsci/jnlp-slave:alpine" image for a
        // separate jnlp container
        // have proved unsuccessful (could not access gihub.com for example)
        podTemplate.getContainers().get(0).setName("jnlp");
        // podTemplate.setInstanceCap(Integer.MAX_VALUE);
        podTemplate.setName(name);
        podTemplate.setLabel(label);
        podTemplate.setAlwaysPullImage(true);
        podTemplate.setCommand("");
        podTemplate.setArgs("${computer.jnlpmac} ${computer.name}");
        podTemplate.setRemoteFs("/tmp");
        String podName = System.getenv().get("HOSTNAME");
        if (podName != null) {
            AlaudaDevOpsClient client = getAuthenticatedAlaudaClient();

            Pod pod = null;
            if(client != null) {
                pod = client.pods().withName(podName).get();
            }

            if (pod != null) {
                podTemplate.setServiceAccount(pod.getSpec().getServiceAccountName());
            }
        }

        return podTemplate;
    }
}
