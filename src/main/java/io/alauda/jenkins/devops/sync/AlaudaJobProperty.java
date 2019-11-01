package io.alauda.jenkins.devops.sync;

import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.kubernetes.client.models.V1ObjectMeta;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;

import java.util.HashMap;
import java.util.Map;

public interface AlaudaJobProperty {
    String getUid();

    void setUid(String uid);

    String getName();

    void setName(String name);

    String getNamespace();

    void setNamespace(String namespace);

    String getResourceVersion();

    void setResourceVersion(String resourceVersion);

    String getContextAnnotation();

    void setContextAnnotation(String contextAnnotation);

    default boolean isValid() {
        return StringUtils.isNotBlank(getNamespace()) &&
                StringUtils.isNotBlank(getName()) &&
                StringUtils.isNotBlank(getUid());
    }

    default V1alpha1PipelineConfig getV1alpha1PipelineConfig() {
        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister()
                .namespace(getNamespace()).get(getName());
        if (pc != null && pc.getMetadata().getUid().equals(getUid())) {
            return pc;
        }
        return null;
    }

    /**
     * Get all annotations which start with {@link Annotations#ALAUDA_PIPELINE_CONTEXT}
     *
     * @param pc instance of V1alpha1PipelineConfig
     * @return annotation as the JSON format
     */
    default String generateAnnotationAsJSON(V1alpha1PipelineConfig pc) {
        V1ObjectMeta meta = pc.getMetadata();
        Map<String, String> Annotation = meta.getAnnotations();
        String contextAnnotation = "{}";
        if (Annotation != null) {
            Map<String, String> annotationResult = new HashMap<>();
            for (String key : Annotation.keySet()) {
                if (key.startsWith(Annotations.ALAUDA_PIPELINE_CONTEXT)) {
                    annotationResult.put(key, Annotation.get(key));
                }
            }
            contextAnnotation = JSONObject.fromObject(annotationResult).toString();
        }
        return contextAnnotation;
    }
}