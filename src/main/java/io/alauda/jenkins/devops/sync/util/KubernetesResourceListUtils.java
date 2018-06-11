package io.alauda.jenkins.devops.sync.util;

import io.alauda.kubernetes.api.model.HasMetadata;
import io.alauda.kubernetes.api.model.KubernetesResourceList;

/**
 * @author suren
 */
public abstract class KubernetesResourceListUtils {
    public static String getResourceVersion(KubernetesResourceList<HasMetadata> list) {
        String ver = "0";
        if(list != null && list.getItems() != null && list.getItems().size() > 0) {
             ver = list.getMetadata().getResourceVersion();
        }

        return ver;
    }
}
