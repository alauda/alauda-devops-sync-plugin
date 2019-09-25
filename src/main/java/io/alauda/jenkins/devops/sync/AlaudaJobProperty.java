package io.alauda.jenkins.devops.sync;

import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
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

    default PipelineConfig getPipelineConfig() {
        PipelineConfig pc = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs()
                .inNamespace(getNamespace()).withName(getName()).get();
        if (pc != null && pc.getMetadata().getUid().equals(getUid())) {
            return pc;
        }
        return null;
    }

    /**
     * Get all annotations which start with {@link Annotations#ALAUDA_PIPELINE_CONTEXT}
     * @param pc instance of PipelineConfig
     * @return annotation as the JSON format
     */
    default String generateAnnotationAsJSON(PipelineConfig pc) {
        ObjectMeta meta = pc.getMetadata();
        Map<String, String> Annotation = meta.getAnnotations();
        String contextAnnotation = "{}";
        if(Annotation!=null){
            Map<String,String> annotationResult = new HashMap<>();
            for(String key:Annotation.keySet()){
                if(key.startsWith(Annotations.ALAUDA_PIPELINE_CONTEXT)){
                    annotationResult.put(key, Annotation.get(key));
                }
            }
            contextAnnotation = JSONObject.fromObject(annotationResult).toString();
        }
        return contextAnnotation;
    }
}
