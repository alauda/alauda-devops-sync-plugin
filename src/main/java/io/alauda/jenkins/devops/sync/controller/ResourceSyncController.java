package io.alauda.jenkins.devops.sync.controller;

import hudson.ExtensionList;
import hudson.ExtensionPoint;
import io.alauda.devops.java.client.extend.controller.builder.ControllerManangerBuilder;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.ProcessorListener;
import io.kubernetes.client.informer.cache.SharedProcessor;

import javax.annotation.Nonnull;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;

public interface ResourceSyncController extends ExtensionPoint {

    void add(ControllerManangerBuilder managerBuilder, SharedInformerFactory factory);

    @Nonnull
    static ExtensionList<ResourceSyncController> all() {
        return ExtensionList.lookup(ResourceSyncController.class);
    }

    // kubernetes java client has already removed the capacity limit, we need to remove this when java client releasing a new version
    @SuppressWarnings("unchecked")
    default <ApiType> void increaseInformerCapacity(SharedIndexInformer<ApiType> sharedIndexInformer) {
        try {
            Field sharedProcessorField = sharedIndexInformer.getClass().getDeclaredField("processor");
            sharedProcessorField.setAccessible(true);
            SharedProcessor<ApiType> processor = (SharedProcessor<ApiType>) sharedProcessorField.get(sharedIndexInformer);

            Field listenersField = processor.getClass().getDeclaredField("listeners");
            listenersField.setAccessible(true);
            List<ProcessorListener<ApiType>> processorListeners = (List<ProcessorListener<ApiType>>) listenersField.get(processor);
            for (ProcessorListener<ApiType> processorListener : processorListeners) {
                Field queueField = processorListener.getClass().getDeclaredField("queue");
                queueField.setAccessible(true);
                queueField.set(processorListener, new LinkedBlockingQueue<ProcessorListener.Notification>());
            }
        } catch (Throwable throwable) {
            throwable.printStackTrace();
        }
    }

}
