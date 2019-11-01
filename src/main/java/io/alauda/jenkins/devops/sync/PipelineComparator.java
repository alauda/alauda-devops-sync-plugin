package io.alauda.jenkins.devops.sync;

import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.isCancelled;

import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import java.io.Serializable;
import java.util.Comparator;

public class PipelineComparator implements Comparator<V1alpha1Pipeline>, Serializable {
  private PipelineNumComparator numComparator = new PipelineNumComparator();

  @Override
  public int compare(V1alpha1Pipeline p1, V1alpha1Pipeline p2) {
    // Order so cancellations are first in list so we can stop
    // processing build list when build run policy is
    // SerialLatestOnly and job is currently building.
    Boolean p1Cancelled =
        p1.getStatus() != null && p1.getStatus().getPhase() != null && isCancelled(p1.getStatus());
    Boolean p2Cancelled =
        p2.getStatus() != null && p2.getStatus().getPhase() != null && isCancelled(p2.getStatus());
    // Inverse comparison as boolean comparison would put false
    // before true. Could have inverted both cancellation
    // states but this removes that step.
    int cancellationCompare = p2Cancelled.compareTo(p1Cancelled);
    if (cancellationCompare != 0) {
      return cancellationCompare;
    }

    return numComparator.compare(p1, p2);
  }
}
