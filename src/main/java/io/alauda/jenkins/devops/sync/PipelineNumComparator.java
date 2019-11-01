package io.alauda.jenkins.devops.sync;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER;

import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import java.io.Serializable;
import java.util.Comparator;
import java.util.Map;

public class PipelineNumComparator implements Comparator<V1alpha1Pipeline>, Serializable {
  @Override
  public int compare(V1alpha1Pipeline p1, V1alpha1Pipeline p2) {
    Map<String, String> p1Anno = p1.getMetadata().getAnnotations();
    Map<String, String> p2Anno = p2.getMetadata().getAnnotations();
    if (p1Anno == null || p2Anno == null) {
      return 0;
    }

    String p1Num = p1Anno.get(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER);
    String p2Num = p2Anno.get(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER);
    if (p1Num == null || p2Num == null) {
      return 0;
    }

    try {
      return Long.compare(Long.parseLong(p1Num), Long.parseLong(p2Num));
    } catch (Exception e) {
      //            logger.log(Level.FINE, "onInitialPipelines", t);
    }

    return 0;
  }
}
