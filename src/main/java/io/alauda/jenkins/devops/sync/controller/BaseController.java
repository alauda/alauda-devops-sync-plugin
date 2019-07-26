package io.alauda.jenkins.devops.sync.controller;

import io.alauda.devops.java.client.extend.workqueue.DefaultRateLimitingQueue;
import io.alauda.devops.java.client.extend.workqueue.RateLimitingQueue;
import io.alauda.devops.java.client.extend.workqueue.ratelimiter.DefaultControllerRateLimiter;
import io.alauda.jenkins.devops.support.controller.Controller;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.ApiClient;
import io.kubernetes.client.informer.ResourceEventHandler;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import jenkins.util.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

public abstract class BaseController<ApiType, ApiListType> implements Controller<ApiType, ApiListType> {
    private static final Logger logger = LoggerFactory.getLogger(BaseController.class);

    private RateLimitingQueue<NamespaceName> queue;
    private SharedIndexInformer<ApiType> informer;

    @Override
    public void initialize(ApiClient client, SharedInformerFactory factory) {
        queue = new DefaultRateLimitingQueue<>(Executors.newSingleThreadExecutor(), new DefaultControllerRateLimiter<>());
        informer = newInformer(client, factory);

        EnqueueResourceEventHandler<ApiType, NamespaceName> handler = newHandler();
        if (handler != null) {
            informer.addEventHandler(new ResourceEventHandler<ApiType>() {
                @Override
                public void onAdd(ApiType obj) {
                    handler.onAdd(obj, queue);
                }

                @Override
                public void onUpdate(ApiType oldObj, ApiType newObj) {
                    handler.onUpdate(oldObj, newObj, queue);
                }

                @Override
                public void onDelete(ApiType obj, boolean deletedFinalStateUnknown) {
                    handler.onDelete(obj, deletedFinalStateUnknown, queue);
                }
            });
        }
    }

    public abstract SharedIndexInformer<ApiType> newInformer(ApiClient client, SharedInformerFactory factory);

    public EnqueueResourceEventHandler<ApiType, NamespaceName> newHandler() {
        return null;
    }

    @Override
    public void start() {
        logger.info("Starting Controller {}", getControllerName());

        try {
            waitControllerReady();
        } catch (Exception e) {
            shutDown(e);
            return;
        }

        ScheduledFuture future = Timer.get().scheduleAtFixedRate(() -> {
            //noinspection StatementWithEmptyBody
            while (processNextWorkItem()) {
            }
        }, 0, 1, TimeUnit.SECONDS);


        try {
            future.get();
            logger.info("{} is stopped", getControllerName());
            shutDown(null);
        } catch (InterruptedException | ExecutionException e) {
            logger.error("{} is stopped unexpectedly, reason: {}", getControllerName(), e.getMessage());
            shutDown(e);
        }
    }


    private boolean processNextWorkItem() {
        NamespaceName namespaceName;
        try {
            namespaceName = queue.get();
            if (namespaceName == null) {
                if (queue.isShuttingDown()) {
                    logger.error("[{}]Unable to get item from workqueue, reason: workqueue is shutting down", getControllerName());
                    return false;
                }

                logger.warn("[{}] Get null item from workqueue, might have potential bug", getControllerName());
                return false;
            }
        } catch (InterruptedException e) {
            logger.error("[{}] Unable to get item from workqueue, reason: {}", getControllerName());
            return false;
        }

        try {
            ReconcileResult result = reconcile(namespaceName);
            if (!result.requeueAfter.isZero() && !result.requeueAfter.isNegative()) {
                logger.debug("[{}] Requeue {} '{}/{}' after {} seconds", getControllerName(), getType().getTypeName(), namespaceName.getNamespace(), namespaceName.getName(), result.requeueAfter.getSeconds());
                queue.addAfter(namespaceName, result.requeueAfter);
                return true;
            } else if (result.requeue) {
                logger.debug("[{}] Requeue {} '{}/{}'", getControllerName(), getType().getTypeName(), namespaceName.getNamespace(), namespaceName.getName());
                queue.addRateLimited(namespaceName);
                return true;
            }

        } catch (Throwable e) {
            queue.addRateLimited(namespaceName);
            logger.error("[{}] Reconciler error for {} '{}/{}', reason: {}", getControllerName(), getType().getTypeName(), namespaceName.getNamespace(), namespaceName.getName(), e.getMessage());
            return false;
        } finally {
            queue.done(namespaceName);
        }

        logger.debug("[{}] Successfully reconciled {} '{}/{}'", getControllerName(), getType().getTypeName(), namespaceName.getNamespace(), namespaceName.getName());
        queue.forget(namespaceName);
        return true;
    }

    public void waitControllerReady() throws Exception {
    }

    @Override
    public void shutDown(Throwable reason) {
        if (reason == null) {
            logger.warn("[{}] will be shutdown, reason: might be shutdown by user", getControllerName());
        } else {
            logger.warn("[{}] will be shutdown, reason: {}", getControllerName(), reason.getMessage());
        }

        try {
            if (informer != null) {
                informer.stop();
            }
            if (queue != null) {
                queue.shutDown();
            }
        } catch (Throwable e) {
            logger.warn("[{}] Unable to be shutdown reason: {}", getControllerName(), e.getMessage());
        }
    }


    public abstract String getControllerName();

    public ReconcileResult reconcile(NamespaceName namespaceName) throws Exception {
        return new ReconcileResult(false);
    }
}
