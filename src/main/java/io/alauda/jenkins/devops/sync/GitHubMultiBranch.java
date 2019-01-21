package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServiceEnum;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMHeadAuthority;
import jenkins.scm.api.trait.SCMSourceTrait;

import java.lang.reflect.InvocationTargetException;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static io.alauda.jenkins.devops.sync.constants.Constants.GITHUB_FORK_PR_TRUST_TRAIT;

@Extension
public class GitHubMultiBranch implements GitProviderMultiBranch {
    @Override
    public boolean accept(String type) {
        return (CodeRepoServiceEnum.Github.name().equals(type));
    }

    @Override
    public SCMSource getSCMSource(String repoOwner, String repository) {
        try {
            Class<?> scmSource = loadClass(GITHUB_SCM_SOURCE);

            return (SCMSource) scmSource.getConstructor(String.class, String.class).newInstance(repoOwner, repository);
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            e.printStackTrace();
        }

        return null;
    }

    @Override
    public SCMSourceTrait getBranchDiscoverTrait(int code) {
        try {
            Class<?> discoverBranchClz = loadClass(GITHUB_BRANCH_DISCOVERY_TRAIT);
            return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class).newInstance(code);
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public SCMSourceTrait getOriginPRTrait(int code) {
        try {
            Class<?> discoverBranchClz = loadClass(GITHUB_ORIGIN_PR_TRAIT);
            return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class).newInstance(code);
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public SCMSourceTrait getForkPRTrait(int code) {
        try {
            Class<?> discoverBranchClz = loadClass(GITHUB_FORK_PR_TRAIT);
            Class<?> trustClz = loadClass(GITHUB_FORK_PR_TRUST_TRAIT);
            return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class, SCMHeadAuthority.class).newInstance(code, trustClz.newInstance());
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }
}
