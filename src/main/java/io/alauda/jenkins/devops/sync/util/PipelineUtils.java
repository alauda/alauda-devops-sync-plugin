package io.alauda.jenkins.devops.sync.util;

import io.alauda.devops.client.AlaudaDevOpsClient;

public class PipelineUtils {
    public static boolean delete(String namespace, String name) {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return false;
        }

        return client.pipelines().inNamespace(namespace).withName(name).delete();
    }
}
