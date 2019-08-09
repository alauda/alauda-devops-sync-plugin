package io.alauda.jenkins.devops.sync.client;

import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1Status;

public class JenkinsBindingClient implements ResourceClient<V1alpha1JenkinsBinding> {
    private SharedIndexInformer<V1alpha1JenkinsBinding> informer;
    private Lister<V1alpha1JenkinsBinding> lister;

    public JenkinsBindingClient(SharedIndexInformer<V1alpha1JenkinsBinding> informer) {
        this.informer = informer;
        this.lister = new Lister<>(informer.getIndexer());
    }

    @Override
    public SharedIndexInformer<V1alpha1JenkinsBinding> informer() {
        return informer;
    }

    @Override
    public Lister<V1alpha1JenkinsBinding> lister() {
        return lister;
    }

    @Override
    public boolean update(V1alpha1JenkinsBinding oldObj, V1alpha1JenkinsBinding newObj) {
        throw new UnsupportedOperationException("Should not update JenkinsBinding in Jenkins");
    }

    @Override
    public V1alpha1JenkinsBinding create(V1alpha1JenkinsBinding obj) {
        throw new UnsupportedOperationException("Should not create JenkinsBinding in Jenkins");

    }

    @Override
    public V1Status delete(String namespace, String name) {
        throw new UnsupportedOperationException("Should not delete JenkinsBinding in Jenkins");
    }
}
