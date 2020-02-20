package io.alauda.jenkins.devops.sync.mapper.converter;

import hudson.Extension;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServices;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.jenkinsci.plugins.github_branch_source.BranchDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait;
import org.jenkinsci.plugins.github_branch_source.ForkPullRequestDiscoveryTrait.TrustPermission;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
@Restricted(NoExternalUse.class)
public class GitHubMultiBranch implements GitProviderMultiBranch {

  private static final Logger LOGGER = LoggerFactory.getLogger(GitHubMultiBranch.class);

  @Override
  public boolean accept(String type) {
    return (CodeRepoServices.Github.name().equals(type));
  }

  @Override
  public SCMSource getSCMSource(String repoOwner, String repository) {
    return new GitHubSCMSource(repoOwner, repository);
  }

  @Override
  public SCMSourceTrait getBranchDiscoverTrait(int code) {
    return new BranchDiscoveryTrait(code);
  }

  @Override
  public SCMSourceTrait getOriginPRTrait(int code) {
    return new OriginPullRequestDiscoveryTrait(code);
  }

  @Override
  public SCMSourceTrait getForkPRTrait(int code) {
    return new ForkPullRequestDiscoveryTrait(code, new TrustPermission());
  }

  @Override
  public boolean isSourceSame(SCMSource current, SCMSource expected) {
    if (current == null || expected == null) {
      return false;
    }

    if (!current.getClass().equals(expected.getClass())) {
      return false;
    }

    GitHubSCMSource currentSCMSource = ((GitHubSCMSource) current);
    GitHubSCMSource expectedSCMSource = ((GitHubSCMSource) expected);

    return currentSCMSource.getRepoOwner().equals(expectedSCMSource.getRepoOwner())
        && currentSCMSource.getRepository().equals(expectedSCMSource.getRepository());
  }
}
