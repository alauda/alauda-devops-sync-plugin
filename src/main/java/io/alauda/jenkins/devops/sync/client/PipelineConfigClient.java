package io.alauda.jenkins.devops.sync.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.PatchGenerator;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1DeleteOptions;
import io.kubernetes.client.models.V1Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;

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
    public boolean update(V1alpha1PipelineConfig oldPipelineConfig, V1alpha1PipelineConfig newPipelineConfig) {
        String name = oldPipelineConfig.getMetadata().getName();
        String namespace = oldPipelineConfig.getMetadata().getNamespace();

        String patch;
        try {
            patch = new PatchGenerator().generatePatchBetween(oldPipelineConfig, newPipelineConfig);
        } catch (IOException e) {
            logger.warn("Unable to generate patch for PipelineConfig '{}/{}', reason: {}",
                    namespace, name, e.getMessage());
            return false;
        }

        // When use remove op on omitempty empty field, will cause 422 Exception
        List<JsonObject> bodyWithoutRemove = new LinkedList<>();
        List<JsonObject> bodyOnlyRemove = new LinkedList<>();

        JsonArray arr = new Gson().fromJson(patch, JsonArray.class);
        arr.forEach(jsonElement -> {
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
            api.patchNamespacedPipelineConfig(
                    name,
                    namespace,
                    bodyWithoutRemove,
                    null,
                    null);
        } catch (ApiException e) {
            logger.warn(String.format("Unable to patch PipelineConfig '%s/%s', reason: %s, body: %s",
                    namespace, name, e.getMessage(), e.getResponseBody()), e);
            return false;
        }
        try {
            api.patchNamespacedPipelineConfig(
                    name,
                    namespace,
                    bodyOnlyRemove,
                    null,
                    null);
        } catch (ApiException e) {
            logger.warn(String.format("Unable to patch PipelineConfig '%s/%s', reason: %s, body: %s",
                    namespace, name, e.getMessage(), e.getResponseBody()), e);
            return false;
        }
        return true;
    }

    @Override
    public V1alpha1PipelineConfig create(V1alpha1PipelineConfig pipelineConfig) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();
        try {
            return api.createNamespacedPipelineConfig(pipelineConfig.getMetadata().getNamespace(), pipelineConfig, null, null, null);
        } catch (ApiException e) {
            logger.warn(String.format("Unable to create PipelineConfig '%s/%s', reason: %s", pipelineConfig.getMetadata().getNamespace(), pipelineConfig.getMetadata().getName(), e.getMessage()), e);
            return null;
        }
    }

    @Override
    public V1Status delete(String namespace, String name) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        try {
            return api.deleteNamespacedPipelineConfig(name, namespace, null, new V1DeleteOptions(), null, null, null, null);
        } catch (ApiException e) {
            logger.warn(String.format("Unable to delete PipelineConfig '%s/%s', reason: %s", namespace, name, e.getMessage()), e);
            return null;
        }
    }
}
