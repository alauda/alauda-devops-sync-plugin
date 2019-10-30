package io.alauda.jenkins.devops.sync.client;

import io.alauda.devops.java.client.models.V1alpha1CodeRepoBinding;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1Status;
import jenkins.model.Jenkins;

public class CodeRepoBindingClient implements ResourceClient<V1alpha1CodeRepoBinding> {

    private final SharedIndexInformer<V1alpha1CodeRepoBinding> informer;
    private Lister<V1alpha1CodeRepoBinding> lister;

    public CodeRepoBindingClient(SharedIndexInformer<V1alpha1CodeRepoBinding> informer) {
        this.informer = informer;
        this.lister = new Lister<>(informer.getIndexer());
    }

    @Override
    public SharedIndexInformer<V1alpha1CodeRepoBinding> informer() {
        return informer;
    }

    @Override
    public Lister<V1alpha1CodeRepoBinding> lister() {
        return lister;
    }

    @Override
    public boolean update(V1alpha1CodeRepoBinding oldRepository, V1alpha1CodeRepoBinding newPipelineConfig) {
        throw new UnsupportedOperationException("Should not update coderepobinding in Jenkins");
    }

    @Override
    public V1alpha1CodeRepoBinding create(V1alpha1CodeRepoBinding obj) {
        throw new UnsupportedOperationException("Should not create coderepobinding in Jenkins");
    }

    @Override
    public V1Status delete(String namespace, String name) {
        throw new UnsupportedOperationException("Should not delete coderepobinding in Jenkins");
    }
}
