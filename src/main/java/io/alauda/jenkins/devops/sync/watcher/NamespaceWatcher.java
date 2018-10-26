package io.alauda.jenkins.devops.sync.watcher;

import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.Folder;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.Namespace;
import io.alauda.kubernetes.api.model.NamespaceList;
import io.alauda.kubernetes.client.Watcher;
import jenkins.model.Jenkins;

import java.io.IOException;
import java.util.logging.Logger;

import static io.alauda.kubernetes.client.Watcher.Action.DELETED;

public class NamespaceWatcher extends AbstractWatcher implements BaseWatcher {
    private static final Logger logger = Logger.getLogger(NamespaceWatcher.class.getName());
    private WatcherCallback<Namespace> watcherCallback;

    @Override
    public WatcherCallback getWatcherCallback() {
        return watcherCallback;
    }

    @Override
    public <T> void eventReceived(Watcher.Action action, T resource) {
        if(action != DELETED || !(resource instanceof Namespace)) {
            return;
        }


        Namespace ns = ((Namespace) resource);
        String folderName = ns.getMetadata().getName();

        logger.info(String.format("namespace [%s] watcher receive delete event.", folderName));
        Folder folder = Jenkins.getInstance().getItemByFullName(folderName, Folder.class);
        if(folder == null) {
            logger.warning(String.format("Folder [%s] can't found.", folderName));
            return;
        }

        int itemCount = folder.getItems().size();
        if(itemCount > 0) {
            logger.warning(String.format("Do not delete folder that still has items, count %s.", itemCount));

            AbstractFolderProperty alaudaFolderProperty = new AlaudaFolderProperty(true);
            try {
                folder.addProperty(alaudaFolderProperty);
            } catch (IOException e) {
                e.printStackTrace();
            }

            return;
        }

        try {
            folder.delete();
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void watch() {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            stop();
            logger.severe("client is null, when watch namespace");
            return;
        }

        NamespaceList list = client.namespaces().list();
        String ver = "0";
        if(list != null) {
            ver = list.getMetadata().getResourceVersion();
        }

        watcherCallback = new WatcherCallback<Namespace>(this, null);
        setWatcher(client.namespaces().withResourceVersion(ver).watch(watcherCallback));
    }

    @Override
    public void init(String[] namespaces) {
    }
}
