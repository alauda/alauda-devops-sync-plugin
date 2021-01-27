package io.alauda.jenkins.devops.sync.client;

import io.alauda.devops.java.client.models.V1alpha1CodeRepository;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.models.V1Status;

public class CodeRepositoryClient implements ResourceClient<V1alpha1CodeRepository> {

  private final SharedIndexInformer<V1alpha1CodeRepository> informer;
  private Lister<V1alpha1CodeRepository> lister;

  public CodeRepositoryClient(SharedIndexInformer<V1alpha1CodeRepository> informer) {
    this.informer = informer;
    this.lister = new Lister<>(informer.getIndexer());
  }

  @Override
  public SharedIndexInformer<V1alpha1CodeRepository> informer() {
    return informer;
  }

  @Override
  public Lister<V1alpha1CodeRepository> lister() {
    return lister;
  }

  @Override
  public boolean update(
      V1alpha1CodeRepository oldRepository, V1alpha1CodeRepository newPipelineConfig) {
    throw new UnsupportedOperationException("Should not update code repository in Jenkins");
  }

  @Override
  public V1alpha1CodeRepository create(V1alpha1CodeRepository obj) {
    throw new UnsupportedOperationException("Should not create code repository in Jenkins");
  }

  @Override
  public V1Status delete(String namespace, String name) {
    throw new UnsupportedOperationException("Should not delete code repository in Jenkins");
  }
}
