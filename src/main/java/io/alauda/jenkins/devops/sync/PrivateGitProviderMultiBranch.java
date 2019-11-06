package io.alauda.jenkins.devops.sync;

import io.alauda.devops.java.client.models.V1alpha1CodeRepository;
import io.alauda.jenkins.devops.sync.mapper.converter.GitProviderMultiBranch;
import jenkins.scm.api.SCMSource;

/**
 * A self-hosted git provider is different from a public one. The private one need a private URL.
 */
public interface PrivateGitProviderMultiBranch extends GitProviderMultiBranch {
  SCMSource getSCMSource(String server, String repoOwner, String repository);

  // It's necessary for a private git provider
  default SCMSource getSCMSource(String repoOwner, String repository) {
    throw new UnsupportedOperationException();
  }

  default String getServerName(V1alpha1CodeRepository repository) {
    return repository.getMetadata().getLabels().get("codeRepoService");
  }
}
