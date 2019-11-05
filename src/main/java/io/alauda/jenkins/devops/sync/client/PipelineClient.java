package io.alauda.jenkins.devops.sync.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.alauda.jenkins.devops.sync.event.PipelineEvents;
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

public class PipelineClient implements ResourceClient<V1alpha1Pipeline> {

  private static final Logger logger = LoggerFactory.getLogger(PipelineClient.class);

  private SharedIndexInformer<V1alpha1Pipeline> informer;
  private Lister<V1alpha1Pipeline> lister;

  public PipelineClient(SharedIndexInformer<V1alpha1Pipeline> informer) {
    this.informer = informer;
    this.lister = new Lister<>(informer.getIndexer());
  }

  @Override
  public SharedIndexInformer<V1alpha1Pipeline> informer() {
    return informer;
  }

  @Override
  public Lister<V1alpha1Pipeline> lister() {
    return lister;
  }

  @Override
  public boolean update(V1alpha1Pipeline oldPipeline, V1alpha1Pipeline newPipeline) {
    String patch;
    try {
      patch = new PatchGenerator().generatePatchBetween(oldPipeline, newPipeline);
    } catch (IOException e) {
      logger.warn(
          "Unable to generate patch for Pipeline '{}/{}', reason: {}",
          newPipeline.getMetadata().getNamespace(),
          newPipeline.getMetadata().getName(),
          e.getMessage());
      return false;
    }

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

    if (!updatePipeline(oldPipeline, bodyWithoutRemove)) {
      return false;
    }
    if (!updatePipeline(oldPipeline, bodyOnlyRemove)) {
      return false;
    }
    return true;
  }

  private boolean updatePipeline(V1alpha1Pipeline pipeline, List<JsonObject> body) {
    String name = pipeline.getMetadata().getName();
    String namespace = pipeline.getMetadata().getNamespace();

    try {
      DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
      V1alpha1Pipeline updatedPipeline = api
          .patchNamespacedPipeline(name, namespace, body, null, null);

      PipelineEvents
          .newBuildUpdatedEvent(updatedPipeline, "Pipeline updated in Jenkins")
          .submit();
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to patch Pipeline '%s/%s', reason: %s, body: %s",
              namespace, name, e.getMessage(), e.getResponseBody()),
          e);
      PipelineEvents.newFailedUpdateBuildEvent(
          pipeline, String.format("Failed update pipeline, message %s", e.getMessage()))
          .submit();
      return false;
    }
    return true;
  }

  @Override
  public V1alpha1Pipeline create(V1alpha1Pipeline pipeline) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    try {
      return api.createNamespacedPipeline(
          pipeline.getMetadata().getNamespace(), pipeline, null, null, null);
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to create Pipeline '%s/%s', reason: %s",
              pipeline.getMetadata().getNamespace(),
              pipeline.getMetadata().getName(),
              e.getMessage()),
          e);
      return null;
    }
  }

  @Override
  public V1Status delete(String namespace, String name) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    try {
      V1Status result = api.deleteNamespacedPipeline(
          name, namespace, null, new V1DeleteOptions(), null, null, null, null);

      if (!result.getStatus().equals("Success")) {
        PipelineEvents.newFailedDeleteBuildEvent(
            namespace,
            name,
            String.format("Failed to delete Pipeline, reason %s", result.getMessage()))
            .submit();
      } else {
        PipelineEvents.newBuildDeletedEvent(namespace, name, "Pipeline deleted in Jenkins")
            .submit();
      }
      return result;
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to delete Pipeline '%s/%s', reason: %s", namespace, name, e.getMessage()),
          e);
      PipelineEvents.newFailedDeleteBuildEvent(
          namespace,
          name,
          String.format("Failed to delete Pipeline, reason %s", e.getMessage()))
          .submit();
      return null;
    }
  }
}
