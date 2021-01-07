package io.alauda.jenkins.devops.sync.client;

import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
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

public class PipelineConfigClient implements ResourceClient<V1alpha1PipelineConfig> {
  private static final Logger logger = LoggerFactory.getLogger(PipelineConfigClient.class);

  private final SharedIndexInformer<V1alpha1PipelineConfig> informer;
  private final Lister<V1alpha1PipelineConfig> lister;

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
    String name = oldPipelineConfig.getMetadata().getName();
    String namespace = oldPipelineConfig.getMetadata().getNamespace();

    String patch;
    try {
      patch = new PatchGenerator().generatePatchBetween(oldPipelineConfig, newPipelineConfig);
    } catch (IOException e) {
      logger.warn(
          "Unable to generate patch for PipelineConfig '{}/{}', reason: {}",
          namespace,
          name,
          e.getMessage());
      return false;
    }

    logger.info("PipelineConfig update patch: " + patch);

    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    try {
      PatchUtils.patch(
          V1alpha1PipelineConfig.class,
          () ->
              api.patchNamespacedPipelineConfigCall(
                  name, namespace, new V1Patch(patch), null, null, null, null, null),
          V1Patch.PATCH_FORMAT_JSON_PATCH,
          api.getApiClient());
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to patch PipelineConfig '%s/%s', reason: %s, body: %s",
              namespace, name, e.getMessage(), e.getResponseBody()),
          e);
      return false;
    }
    return true;
  }

  @Override
  public V1alpha1PipelineConfig create(V1alpha1PipelineConfig pipelineConfig) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
    try {
      return api.createNamespacedPipelineConfig(
          pipelineConfig.getMetadata().getNamespace(), pipelineConfig, null, null, null);
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to create PipelineConfig '%s/%s', reason: %s",
              pipelineConfig.getMetadata().getNamespace(),
              pipelineConfig.getMetadata().getName(),
              e.getMessage()),
          e);
      return null;
    }
  }

  @Override
  public V1Status delete(String namespace, String name) {
    DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

    try {
      return api.deleteNamespacedPipelineConfig(
          name, namespace, null, null, null, null, null, new V1DeleteOptions());
    } catch (ApiException e) {
      logger.warn(
          String.format(
              "Unable to delete PipelineConfig '%s/%s', reason: %s",
              namespace, name, e.getMessage()),
          e);
      return null;
    }
  }
}
