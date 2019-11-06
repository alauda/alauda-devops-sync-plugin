package io.alauda.jenkins.devops.sync;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import hudson.Extension;
import hudson.plugins.git.extensions.impl.CloneOption;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServices;
import java.lang.reflect.InvocationTargetException;
import jenkins.plugins.git.traits.CloneOptionTrait;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMHeadAuthority;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
@Restricted(NoExternalUse.class)
public class GitLabProviderMultiBranch implements PrivateGitProviderMultiBranch {
  private static final Logger LOGGER = LoggerFactory.getLogger(GitLabProviderMultiBranch.class);

  @Override
  public boolean accept(String type) {
    return (CodeRepoServices.Gitlab.name().equals(type));
  }

  @Override
  public SCMSource getSCMSource(String server, String repoOwner, String repository) {
    try {
      Class<?> scmSource = loadClass(GITLAB_SCM_SOURCE);

      return (SCMSource)
          scmSource
              .getConstructor(String.class, String.class, String.class)
              .newInstance(server, repoOwner, String.format("%s/%s", repoOwner, repository));
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      LOGGER.warn("Exception happened while getSCMSource", e);
    }

    return null;
  }

  @Override
  public SCMSourceTrait getBranchDiscoverTrait(int code) {
    try {
      Class<?> discoverBranchClz = loadClass(GITLAB_BRANCH_DISCOVERY_TRAIT);
      return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class).newInstance(code);
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      LOGGER.warn("Exception happened while getBranchDiscoverTrait", e);
    }
    return null;
  }

  @Override
  public SCMSourceTrait getOriginPRTrait(int code) {
    try {
      Class<?> discoverBranchClz = loadClass(GITLAB_ORIGIN_PR_TRAIT);
      return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class).newInstance(code);
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      LOGGER.warn("Exception happened while getOriginPRTrait", e);
    }
    return null;
  }

  @Override
  public SCMSourceTrait getForkPRTrait(int code) {
    try {
      Class<?> discoverBranchClz = loadClass(GITLAB_FORK_PR_TRAIT);
      Class<?> trustClz = loadClass(GITLAB_FORK_PR_TRUST_TRAIT);
      return (SCMSourceTrait)
          discoverBranchClz
              .getConstructor(int.class, SCMHeadAuthority.class)
              .newInstance(code, trustClz.newInstance());
    } catch (ClassNotFoundException
        | NoSuchMethodException
        | InstantiationException
        | IllegalAccessException
        | InvocationTargetException e) {
      LOGGER.warn("Exception happened while getForkPRTrait", e);
    }
    return null;
  }

  @Override
  public CloneOptionTrait getCloneTrait() {
    // if shallow is true, a pr merge problem will occur.
    CloneOption cloneOption = new CloneOption(false, false, null, null);
    cloneOption.setHonorRefspec(true);

    return new CloneOptionTrait(cloneOption);
  }
}
