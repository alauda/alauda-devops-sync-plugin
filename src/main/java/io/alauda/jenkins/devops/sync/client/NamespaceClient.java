package io.alauda.jenkins.devops.sync.client;

import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1Status;

public class NamespaceClient implements ResourceClient<V1Namespace> {
  private SharedIndexInformer<V1Namespace> informer;
  private Lister<V1Namespace> lister;

  public NamespaceClient(SharedIndexInformer<V1Namespace> informer) {
    this.informer = informer;
    this.lister = new Lister<>(informer.getIndexer());
  }

  @Override
  public SharedIndexInformer<V1Namespace> informer() {
    return informer;
  }

  @Override
  public Lister<V1Namespace> lister() {
    return lister;
  }

  @Override
  public boolean update(V1Namespace oldRepository, V1Namespace newPipelineConfig) {
    throw new UnsupportedOperationException("Should not update namespace in Jenkins");
  }

  @Override
  public V1Namespace create(V1Namespace obj) {
    throw new UnsupportedOperationException("Should not create namespace in Jenkins");
  }

  @Override
  public V1Status delete(String namespace, String name) {
    throw new UnsupportedOperationException("Should not delete namespace in Jenkins");
  }
}
