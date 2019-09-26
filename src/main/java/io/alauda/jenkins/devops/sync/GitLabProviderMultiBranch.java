package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServices;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMHeadAuthority;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

@Extension
@Restricted(NoExternalUse.class)
public class GitLabProviderMultiBranch implements PrivateGitProviderMultiBranch {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitLabProviderMultiBranch.class);

    @Override
    public boolean accept(String type) {
        return false;
//        return (CodeRepoServices.Gitlab.name().equals(type));
    }

    @Override
    public SCMSource getSCMSource(String server, String repoOwner, String repository) {
        try {
            Class<?> scmSource = loadClass(GITLAB_SCM_SOURCE);

            return (SCMSource) scmSource.getConstructor(String.class, String.class, String.class)
                    .newInstance(server, repoOwner, repository);
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
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
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
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
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
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
            return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class, SCMHeadAuthority.class).newInstance(code, trustClz.newInstance());
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            LOGGER.warn("Exception happened while getForkPRTrait", e);
        }
        return null;
    }
}
