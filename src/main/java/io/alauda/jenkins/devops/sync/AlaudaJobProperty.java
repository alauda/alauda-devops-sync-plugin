package io.alauda.jenkins.devops.sync;

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
}
