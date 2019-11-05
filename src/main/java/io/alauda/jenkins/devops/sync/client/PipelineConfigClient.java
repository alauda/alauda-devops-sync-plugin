package io.alauda.jenkins.devops.sync.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.sync.event.PipelineConfigEvents;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Status;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PipelineConfigClient implements ResourceClient<V1alpha1PipelineConfig> {

  private static final Logger logger = LoggerFactory.getLogger(PipelineConfigClient.class);

  private SharedIndexInformer<V1alpha1PipelineConfig> informer;
  private Lister<V1alpha1PipelineConfig> lister;

  public PipelineConfigClient(SharedIndexInformer<V1alpha1PipelineConfig> informer) {
    this.informer = informer;
    lister = new Lister<>(informer.getIndexer());
  }

  @Override
  public SharedIndexInformer<V1alpha1PipelineConfig> informer() {
    return informer;
  }

  @Override
  public Lister<V1alpha1PipelineConfig> lister() {
    return lister;
  }

  @Override
  public boolean update(
      V1alpha1PipelineConfig oldPipelineConfig, V1alpha1PipelineConfig newPipelineConfig) {

    String patch;
    try {
      patch = new PatchGenerator().generatePatchBetween(oldPipelineConfig, newPipelineConfig);
    } catch (IOException e) {
      logger.warn(
          "Unable to generate patch for PipelineConfig '{}/{}', reason: {}",
          oldPipelineConfig.getMetadata().getNamespace(),
          oldPipelineConfig.getMetadata().getName(),
          e.getMessage());
      return false;
    }

    logger.info("PipelineConfig update patch: " + patch);

    // When use remove op on omitempty empty field, will cause 422 Exception
    List<JsonObject> bodyWithoutRemove = new LinkedList<>();
    List<JsonObject> bodyOnlyRemove = new LinkedList<>();

    JsonArray arr = new Gson().fromJson(patch, JsonArray.class);
    arr.forEach(
        jsonElement -> {
          JsonElement op = jsonElement.getAsJsonObject().get("op");
          if (op != null) {
            if ("remove".equals(op.getAsString())) {
              bodyOnlyRemove.add(jsonElement.getAsJsonObject());
            } else {
              bodyWithoutRemove.add(jsonElement.getAsJsonObject());
            }
          }
        });

    if (!updatePipelineConfig(oldPipelineConfig, bodyWithoutRemove)) {
      return false;
    }
    if (!updatePipelineConfig(oldPipelineConfig, bodyOnlyRemove)) {
      return false;
    }
    return true;
  }

  private boolean updatePipelineConfig(
      V1alpha1PipelineConfig oldPipelineConfig, List<JsonObject> body) {
    String name = oldPipelineConfig.getMetadata().getName();
    String namespace = oldPipelineConfig.getMetadata().getNamespace();

    try {
      DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
      V1alpha1PipelineConfig updatedPipelineConfig = api
          .patchNamespacedPipelineConfig(name, namespace, body, null, null);

      PipelineConfigEvents.newJobUpdatedEvent(updatedPipelineConfig,
          "PipelineConfig updated in Jenkins")
          .submit();
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to patch PipelineConfig '%s/%s', reason: %s, body: %s",
              namespace, name, e.getMessage(), e.getResponseBody()),
          e);
      PipelineConfigEvents.newFailedUpdateJobEvent(
          oldPipelineConfig,
          String.format(
              "Unable to update PipelineConfig in Jenkins, reason %s", e.getMessage()))
          .submit();
      return true;
    }
    return false;
  }

  @Override
  public V1alpha1PipelineConfig create(V1alpha1PipelineConfig pipelineConfig) {
    throw new UnsupportedOperationException("Should not create PipelineConfig in Jenkins");
  }

  @Override
  public V1Status delete(String namespace, String name) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    try {
      V1Status result = api.deleteNamespacedPipelineConfig(
          name, namespace, null, new V1DeleteOptions(), null, null, null, null);

      if (!result.getStatus().equals("Success")) {
        PipelineConfigEvents.newFailedDeleteJobEvent(
            namespace,
            name,
            String.format("Failed to delete PipelineConfig, reason %s", result.getMessage()))
            .submit();
      } else {
        PipelineConfigEvents
            .newJobDeletedEvent(namespace, name, "PipelineConfig deleted by Jenkins")
            .submit();
      }
      return result;
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to delete PipelineConfig '%s/%s', reason: %s",
              namespace, name, e.getMessage()),
          e);
      PipelineConfigEvents.newFailedDeleteJobEvent(
          namespace,
          name,
          String.format("Failed to delete PipelineConfig, reason %s", e.getMessage()))
          .submit();
      return null;
    }
  }
}
