package io.alauda.jenkins.devops.sync.mapper.converter;

import hudson.ExtensionPoint;
import hudson.PluginManager;
import hudson.plugins.git.extensions.impl.CloneOption;
import jenkins.model.Jenkins;
import jenkins.plugins.git.traits.CloneOptionTrait;
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

  /**
   * clone option trait for this branch
   *
   * @return CloneOption trait<br>
   *     Default behaviours are below:<br>
   *     shallow: true<br>
   *     noTags: false<br>
   *     reference: null<br>
   *     timeout: null<br>
   *     honorRefspec: true<br>
   */
  default CloneOptionTrait getCloneTrait(CloneOption option) {
    if (option == null) {
      CloneOption cloneOption = new CloneOption(false, false, null, null);
      cloneOption.setHonorRefspec(true);
    }

    return new CloneOptionTrait(option);
  }

  boolean isSourceSame(SCMSource current, SCMSource expected);
}
