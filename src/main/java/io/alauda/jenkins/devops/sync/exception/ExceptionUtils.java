package io.alauda.jenkins.devops.sync.exception;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.models.V1Status;

public class ExceptionUtils {

  private ExceptionUtils() {
  }

  /**
   * This method return true if we can find service but cannot find resource in k8s
   *
   * @param exception ApiException
   * @return true if reason of exception if not found
   */
  public static boolean isResourceNotFoundException(ApiException exception) {
    if (exception == null) {
      return false;
    }

    if (exception.getCode() != 404) {
      return false;
    }

    String body = exception.getResponseBody();

    try {
      V1Status status = new Gson().fromJson(body, V1Status.class);

      return status != null && status.getCode() == 404;
    } catch (JsonSyntaxException ignore) {
      return false;
    }
  }
}
