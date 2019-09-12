package io.alauda.jenkins.devops.sync.mapper.converter;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Action;
import hudson.model.TaskListener;
import io.alauda.jenkins.devops.sync.SCMRevisionAction;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServiceEnum;
import jenkins.model.Jenkins;
import jenkins.scm.api.SCMHeadEvent;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.SCMSourceDescriptor;
import jenkins.scm.api.trait.SCMHeadAuthority;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

@Extension
@Restricted(NoExternalUse.class)
public class GitHubMultiBranch implements GitProviderMultiBranch {
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubMultiBranch.class);

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
            LOGGER.warn("Exception happened while getSCMSource", e);
        }

        return null;

//        return new GitHubSCMSource(repoOwner, repository){
//            @NonNull
//            @Override
//            protected List<Action> retrieveActions(@NonNull SCMRevision revision, SCMHeadEvent event, @NonNull TaskListener listener) throws IOException, InterruptedException {
//                List<Action> actions = new ArrayList<Action>(super.retrieveActions(revision, event, listener));
//                actions.add(new SCMRevisionAction(revision));
//                return actions;
//            }
//
//            @Override
//            public SCMSourceDescriptor getDescriptor() {
//                return (SCMSourceDescriptor) Jenkins.getInstance().getDescriptorOrDie(GitHubSCMSource.class);
//            }
//        };
    }

    @Override
    public SCMSourceTrait getBranchDiscoverTrait(int code) {
        try {
            Class<?> discoverBranchClz = loadClass(GITHUB_BRANCH_DISCOVERY_TRAIT);
            return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class).newInstance(code);
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            LOGGER.warn("Exception happened while getBranchDiscoverTrait", e);
        }

        return null;
//        return new BranchDiscoveryTrait(code);
    }

    @Override
    public SCMSourceTrait getOriginPRTrait(int code) {
        try {
            Class<?> discoverBranchClz = loadClass(GITHUB_ORIGIN_PR_TRAIT);
            return (SCMSourceTrait) discoverBranchClz.getConstructor(int.class).newInstance(code);
        } catch (ClassNotFoundException | NoSuchMethodException
                | InstantiationException | IllegalAccessException
                | InvocationTargetException e) {
            LOGGER.warn("Exception happened while getOriginPRTrait", e);
        }
        return null;
//        return new OriginPullRequestDiscoveryTrait(code);
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
            LOGGER.warn("Exception happened while getForkPRTrait", e);
        }
        return null;
//        return new ForkPullRequestDiscoveryTrait(code, new ForkPullRequestDiscoveryTrait.TrustPermission());
    }
}
