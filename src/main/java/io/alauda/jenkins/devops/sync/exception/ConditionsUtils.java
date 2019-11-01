package io.alauda.jenkins.devops.sync.exception;

import io.alauda.devops.java.client.models.V1alpha1Condition;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ConditionsUtils {

  public static V1alpha1Condition convertToCondition(Throwable e) {
    V1alpha1Condition condition = new V1alpha1Condition();
    condition.setMessage(e.getMessage());
    return condition;
  }

  public static V1alpha1Condition convertToCondition(String error) {
    V1alpha1Condition condition = new V1alpha1Condition();
    condition.setMessage(error);
    return condition;
  }

  public static List<V1alpha1Condition> convertToConditinos(Throwable... throwables) {
    return Arrays.stream(throwables)
        .map(ConditionsUtils::convertToCondition)
        .collect(Collectors.toList());
  }

  public static List<V1alpha1Condition> convertToConditions(String... errors) {
    return Arrays.stream(errors)
        .map(ConditionsUtils::convertToCondition)
        .collect(Collectors.toList());
  }
}
