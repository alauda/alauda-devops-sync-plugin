package io.alauda.jenkins.devops.sync.client;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.openapi.models.V1Status;

public interface ResourceClient<ApiType extends KubernetesObject> {
  SharedIndexInformer<ApiType> informer();

  Lister<ApiType> lister();

  boolean update(ApiType oldObj, ApiType newObj);

  ApiType create(ApiType obj);

  V1Status delete(String namespace, String name);
}
