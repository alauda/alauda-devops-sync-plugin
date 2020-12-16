package io.alauda.jenkins.devops.sync.tasks.period;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import io.kubernetes.client.util.common.Collections;
import java.io.IOException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.UnrecoverableKeyException;
import java.security.cert.CertificateEncodingException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import jenkins.model.Jenkins;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesSlave;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplateUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class AgentCleaner extends AsyncPeriodicWork {

  private static final Logger logger = LoggerFactory.getLogger(AgentCleaner.class);

  public AgentCleaner() {
    super("AgentCleaner");
  }

  @Override
  protected void execute(TaskListener listener) throws IOException, InterruptedException {
    logger.debug("Start to check and clean up agents");

    for (Cloud cloud : Jenkins.get().clouds) {
      if (cloud instanceof KubernetesCloud) {
        KubernetesCloud kubernetesCloud = ((KubernetesCloud) cloud);
        try {
          checkAgentForCloud(kubernetesCloud);
        } catch (Exception e) {
          logger.warn("Failed to check agent for cloud {}, reason {}", cloud.name, e.getMessage());
        }
      }
    }
  }

  private void checkAgentForCloud(KubernetesCloud cloud) throws Exception {
    List<Node> nodes = Jenkins.get().getNodes();
    if (Collections.isEmptyCollection(nodes)) {
      return;
    }

    Map<String, String> failedPods =
        cloud
            .connect()
            .pods()
            .list()
            .getItems()
            .stream()
            .filter(pod -> pod.getStatus().getPhase().equals("Failed"))
            .collect(
                Collectors.toMap(
                    pod -> pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName(),
                    pod -> ""));

    nodes
        .stream()
        // only process KubernetesSlave
        .filter(node -> node instanceof KubernetesSlave)
        .map(node -> ((KubernetesSlave) node))
        // proceed if we can find correspond pod in failed pods
        .filter(
            slave ->
                failedPods.containsKey(
                    slave.getNamespace()
                        + "/"
                        + PodTemplateUtils.substituteEnv(slave.getNodeName())))
        .forEach(
            slave -> {
              try {
                logger.info("Will remove agent {} as the pod is failed", slave.getNodeName());
                Jenkins.get().removeNode(slave);
              } catch (IOException e) {
                logger.warn("Failed to remove node {}", slave.getNodeName());
              }

              try {
                String podName = PodTemplateUtils.substituteEnv(slave.getNodeName());
                logger.info("Will delete the pod {}", podName);
                cloud.connect().pods().inNamespace(slave.getNamespace()).withName(podName).delete();
              } catch (IOException
                  | CertificateEncodingException
                  | NoSuchAlgorithmException
                  | KeyStoreException
                  | UnrecoverableKeyException e) {
                logger.warn("Failed to delete pod {}", slave.getNodeName());
              }
            });
  }

  @Override
  public long getRecurrencePeriod() {
    return TimeUnit.MINUTES.toMillis(5);
  }
}
