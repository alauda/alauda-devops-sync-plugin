package io.alauda.jenkins.devops.sync;

import io.alauda.devops.java.client.models.V1alpha1CodeRepoBinding;
import io.alauda.jenkins.devops.sync.mapper.converter.GitProviderMultiBranch;
import jenkins.scm.api.SCMSource;

/**
 * A self-hosted git provider is different from a public one.
 * The private one need a private URL.
 */
public interface PrivateGitProviderMultiBranch extends GitProviderMultiBranch {
    SCMSource getSCMSource(String server, String repoOwner, String repository);

    // It's necessary for a private git provider
    default SCMSource getSCMSource(String repoOwner, String repository) {
        throw new UnsupportedOperationException();
    }

    // based coderepobinding
    default String getServer(V1alpha1CodeRepoBinding binding) {
        return String.format("%s-%s", binding.getMetadata().getNamespace(), binding.getMetadata().getName());
    }
}
