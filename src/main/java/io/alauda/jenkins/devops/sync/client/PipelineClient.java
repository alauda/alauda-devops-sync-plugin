package io.alauda.jenkins.devops.sync.client;

import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.kubernetes.client.custom.V1Patch;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.ApiException;
import io.kubernetes.client.openapi.models.V1DeleteOptions;
import io.kubernetes.client.openapi.models.V1Status;
import io.kubernetes.client.util.PatchUtils;
import java.io.IOException;
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

    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    try {
      PatchUtils.patch(
          V1alpha1Pipeline.class,
          () ->
              api.patchNamespacedPipelineCall(
                  name, namespace, new V1Patch(patch), null, null, null, null, null),
          V1Patch.PATCH_FORMAT_JSON_PATCH,
          api.getApiClient());
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
          name, namespace, null, null, null, null, null, new V1DeleteOptions());
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to delete Pipeline '%s/%s', reason: %s", namespace, name, e.getMessage()),
          e);
      return null;
    }
  }
}
