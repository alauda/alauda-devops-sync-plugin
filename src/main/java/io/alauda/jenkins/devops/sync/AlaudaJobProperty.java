package io.alauda.jenkins.devops.sync;

import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.PipelineConfig;
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

    default PipelineConfig getPipelineConfig() {
        PipelineConfig pc = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs()
                .inNamespace(getNamespace()).withName(getName()).get();
        if (pc != null && pc.getMetadata().getUid().equals(getUid())) {
            return pc;
        }
        return null;
    }
}
