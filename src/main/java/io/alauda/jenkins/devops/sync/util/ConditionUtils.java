package io.alauda.jenkins.devops.sync.util;

import io.alauda.devops.java.client.models.V1alpha1Condition;
import java.util.List;
import javax.annotation.Nullable;

public class ConditionUtils {

  @Nullable
  public static V1alpha1Condition getCondition(
      List<V1alpha1Condition> conditions, String conditionType) {
    if (conditions == null) {
      return null;
    }

    return conditions
        .stream()
        .filter(cond -> cond.getType().equals(conditionType))
        .findAny()
        .orElse(null);
  }
}
