package io.alauda.jenkins.devops.sync.client;

import io.alauda.devops.java.client.models.V1alpha1CodeRepoService;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1Status;

public class CodeRepoServiceClient implements ResourceClient<V1alpha1CodeRepoService> {

    private final SharedIndexInformer<V1alpha1CodeRepoService> informer;
    private Lister<V1alpha1CodeRepoService> lister;

    public CodeRepoServiceClient(SharedIndexInformer<V1alpha1CodeRepoService> informer) {
        this.informer = informer;
        this.lister = new Lister<>(informer.getIndexer());
    }

    @Override
    public SharedIndexInformer<V1alpha1CodeRepoService> informer() {
        return informer;
    }

    @Override
    public Lister<V1alpha1CodeRepoService> lister() {
        return lister;
    }

    @Override
    public boolean update(V1alpha1CodeRepoService oldRepository, V1alpha1CodeRepoService newPipelineConfig) {
        throw new UnsupportedOperationException("Should not update codereposervice in Jenkins");
    }

    @Override
    public V1alpha1CodeRepoService create(V1alpha1CodeRepoService obj) {
        throw new UnsupportedOperationException("Should not create codereposervice in Jenkins");
    }

    @Override
    public V1Status delete(String namespace, String name) {
        throw new UnsupportedOperationException("Should not delete codereposervice in Jenkins");
    }
}
