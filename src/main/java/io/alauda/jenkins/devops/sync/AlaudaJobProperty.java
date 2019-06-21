package io.alauda.jenkins.devops.sync;

import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.controller.PipelineConfigController;
import org.apache.commons.lang.StringUtils;

public interface AlaudaJobProperty {
    String getUid();

    void setUid(String uid);

    String getName();

    void setName(String name);

    String getNamespace();

    void setNamespace(String namespace);

    String getResourceVersion();

    void setResourceVersion(String resourceVersion);

    default boolean isValid() {
        return StringUtils.isNotBlank(getNamespace()) &&
                StringUtils.isNotBlank(getName()) &&
                StringUtils.isNotBlank(getUid());
    }

    default V1alpha1PipelineConfig getPipelineConfig() {
        V1alpha1PipelineConfig pc = PipelineConfigController.getCurrentPipelineConfigController().getPipelineConfig(getNamespace(), getName());

        if (pc != null && pc.getMetadata().getUid().equals(getUid())) {
            return pc;
        }
        return null;
    }
}
