package io.alauda.jenkins.devops.sync.mapper.converter;

import hudson.Extension;
import hudson.plugins.git.extensions.impl.CloneOption;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServices;
import io.jenkins.plugins.gitlabbranchsource.BranchDiscoveryTrait;
import io.jenkins.plugins.gitlabbranchsource.ForkMergeRequestDiscoveryTrait;
import io.jenkins.plugins.gitlabbranchsource.ForkMergeRequestDiscoveryTrait.TrustPermission;
import io.jenkins.plugins.gitlabbranchsource.GitLabSCMSource;
import io.jenkins.plugins.gitlabserverconfig.servers.GitLabServers;
import jenkins.plugins.git.traits.CloneOptionTrait;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import org.jenkinsci.plugins.github_branch_source.OriginPullRequestDiscoveryTrait;
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
    GitLabServers servers = GitLabServers.get();
    if (servers.findServer(server) == null) {
      return null;
    }

    return new GitLabSCMSource(server, repoOwner, String.format("%s/%s", repoOwner, repository));
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
    return new ForkMergeRequestDiscoveryTrait(code, new TrustPermission());
  }

  @Override
  public CloneOptionTrait getCloneTrait() {
    // if shallow is true, a pr merge problem will occur.
    // err message:
    //
    // hudson.plugins.git.GitException: Command "git merge 51aa62cffd8bef407f281ba80bdd8274014a84c9"
    // returned status code 128:
    // stdout:
    // stderr: fatal: refusing to merge unrelated histories
    // http://10.0.128.67:32001/job/lz-test/job/lz-test-demo/view/change-requests/job/MR-10-merge/1/console
    CloneOption cloneOption = new CloneOption(false, false, null, null);
    cloneOption.setHonorRefspec(true);

    return new CloneOptionTrait(cloneOption);
  }

  @Override
  public boolean isSourceSame(SCMSource current, SCMSource expected) {
    if (current == null || expected == null) {
      return false;
    }

    if (!current.getClass().equals(expected.getClass())) {
      return false;
    }

    GitLabSCMSource currentSCMSource = ((GitLabSCMSource) current);
    GitLabSCMSource expectedSCMSource = ((GitLabSCMSource) expected);

    return currentSCMSource.getServerName().equals(expectedSCMSource.getServerName())
        && currentSCMSource.getProjectOwner().equals(expectedSCMSource.getProjectOwner())
        && currentSCMSource.getProjectPath().equals(expectedSCMSource.getProjectPath());
  }
}
