package io.alauda.jenkins.devops.sync.controller;

import io.alauda.devops.java.client.extend.workqueue.WorkQueue;

public interface EnqueueResourceEventHandler<ApiType, T> {
    void onAdd(ApiType obj, WorkQueue<T> queue);

    void onUpdate(ApiType oldObj, ApiType newObj, WorkQueue<T> queue);

    void onDelete(ApiType obj, boolean deletedFinalStateUnknown, WorkQueue<T> queue);
}
