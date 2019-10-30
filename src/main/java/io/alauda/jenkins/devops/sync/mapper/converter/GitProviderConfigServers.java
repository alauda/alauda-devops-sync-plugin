package io.alauda.jenkins.devops.sync.mapper.converter;

import hudson.ExtensionPoint;
import hudson.PluginManager;
import io.alauda.devops.java.client.models.V1alpha1CodeRepoBinding;
import jenkins.model.Jenkins;

public interface GitProviderConfigServers extends ExtensionPoint {
    boolean accept(String type);

    default Class<?> loadClass(String clazz) throws ClassNotFoundException {
        PluginManager pluginMgr = Jenkins.getInstance().getPluginManager();
        if (pluginMgr != null) {
            ClassLoader loader = pluginMgr.uberClassLoader;
            if (loader != null) {
                return loader.loadClass(clazz);
            }
        }

        return null;
    }

    boolean createOrUpdateServer(V1alpha1CodeRepoBinding binding);

    boolean deleteServer(String namespace, String name);
}
