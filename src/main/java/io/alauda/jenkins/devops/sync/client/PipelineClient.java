package io.alauda.jenkins.devops.sync.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.utils.PatchGenerator;
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
    String name = oldPipeline.getMetadata().getName();
    String namespace = oldPipeline.getMetadata().getNamespace();

    String patch;
    try {
      patch = new PatchGenerator().generatePatchBetween(oldPipeline, newPipeline);
    } catch (IOException e) {
      logger.warn(
          "Unable to generate patch for Pipeline '{}/{}', reason: {}",
          namespace,
          name,
          e.getMessage());
      return false;
    }

    logger.debug("Pipeline patch {}", patch);

    // When use remove op on omitempty empty field, will cause 422 Exception
    List<JsonObject> bodyWithoutRemove = new LinkedList<>();
    List<JsonObject> bodyOnlyRemove = new LinkedList<>();

    JsonArray arr = new Gson().fromJson(patch, JsonArray.class);
    if (arr.size() == 0) {
      logger.debug(
          "Skip to patch Pipeline '{}/{}' as the patch content {} is empty",
          namespace,
          name,
          patch);
      return true;
    }

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

    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    try {
      api.patchNamespacedPipeline(name, namespace, bodyWithoutRemove, null, null);
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to patch Pipeline '%s/%s', reason: %s, body: %s",
              namespace, name, e.getMessage(), e.getResponseBody()),
          e);
      return false;
    }
    try {
      api.patchNamespacedPipeline(name, namespace, bodyOnlyRemove, null, null);
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to patch Pipeline '%s/%s', reason: %s, body: %s",
              namespace, name, e.getMessage(), e.getResponseBody()),
          e);
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
              pipeline.getMetadata().getGenerateName(),
              e.getResponseBody()),
          e);
      return null;
    }
  }

  @Override
  public V1Status delete(String namespace, String name) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    try {
      return api.deleteNamespacedPipeline(
          name, namespace, null, new V1DeleteOptions(), null, null, null, null);
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to delete Pipeline '%s/%s', reason: %s", namespace, name, e.getMessage()),
          e);
      return null;
    }
  }
}
