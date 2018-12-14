package io.alauda.jenkins.devops.sync;

import hudson.ExtensionPoint;
import hudson.PluginManager;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;

public interface GitProviderMultiBranch extends ExtensionPoint {
    boolean accept(String type);

    SCMSource getSCMSource(String repoOwner, String repository);

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

    SCMSourceTrait getBranchDiscoverTrait(int code);

    SCMSourceTrait getOriginPRTrait(int code);

    SCMSourceTrait getForkPRTrait(int code);
}
