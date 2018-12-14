package io.alauda.jenkins.devops.sync.listener;

import hudson.ExtensionPoint;
import hudson.model.Item;

public interface ItemEventHandler<T extends Item> extends ExtensionPoint {
    boolean accept(Item item);

    void onCreated(T item);

    void onUpdated(T item);

    void onDeleted(T item);
}
