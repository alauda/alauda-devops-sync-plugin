package io.alauda.jenkins.devops.sync.mapper.converter;

import com.cloudbees.jenkins.plugins.bitbucket.BitbucketSCMSource;
import com.cloudbees.jenkins.plugins.bitbucket.BranchDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait;
import com.cloudbees.jenkins.plugins.bitbucket.ForkPullRequestDiscoveryTrait.TrustTeamForks;
import com.cloudbees.jenkins.plugins.bitbucket.OriginPullRequestDiscoveryTrait;
import hudson.Extension;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServices;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
@Restricted(NoExternalUse.class)
public class BitbucketMultiBranch implements GitProviderMultiBranch {

  private static final Logger LOGGER = LoggerFactory.getLogger(BitbucketMultiBranch.class);

  @Override
  public boolean accept(String type) {
    return (CodeRepoServices.Bitbucket.name().equals(type));
  }

  @Override
  public SCMSource getSCMSource(String repoOwner, String repository) {
    return new BitbucketSCMSource(repoOwner, repository);
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
    return new ForkPullRequestDiscoveryTrait(code, new TrustTeamForks());
  }

  @Override
  public boolean isSourceSame(SCMSource current, SCMSource expected) {
    if (current == null || expected == null) {
      return false;
    }

    if (!current.getClass().equals(expected.getClass())) {
      return false;
    }

    BitbucketSCMSource currentSCMSource = ((BitbucketSCMSource) current);
    BitbucketSCMSource expectedSCMSource = ((BitbucketSCMSource) expected);

    return currentSCMSource.getRepoOwner().equals(expectedSCMSource.getRepoOwner())
        && currentSCMSource.getRepository().equals(expectedSCMSource.getRepository());
  }
}
