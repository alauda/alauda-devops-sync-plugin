package io.alauda.jenkins.devops.sync.listener;

import hudson.model.Item;

public interface ItemEventHandler {
    boolean accept(Item item);

    void onCreated(Item item);

    void onUpdated(Item item);

    void onDeleted(Item item);
}
