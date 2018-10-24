package io.alauda.jenkins.devops.sync.watcher;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.model.TopLevelItem;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
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

        try {
            folder.delete();
        } catch (InterruptedException | IOException e) {
            e.printStackTrace();
        }
    }

    // TODO 这里有个缺陷，当删除命名空间后，相关的资源将会全部删除，也就无法查询了
//    private boolean isBinding(Namespace ns) {
//        String jenkinsService = ResourcesCache.getInstance().getJenkinsService();
//        String nsName = ns.getMetadata().getName();
//        logger.info(String.format("jenkins service: %s.", jenkinsService));
//
//        JenkinsBindingList list = AlaudaUtils.getAlaudaClient().jenkinsBindings()
//                .inNamespace(nsName).list();
//        if(list != null && list.getItems() != null && list.getItems().size() > 0) {
//            for(JenkinsBinding binding : list.getItems()) {
//                String bindingJenkins = binding.getSpec().getJenkins().getName();
//
//                logger.info(String.format("binding jenkins %s", bindingJenkins));
//
//                if(jenkinsService.equals(bindingJenkins)) {
//                    return true;
//                }
//            }
//        } else {
//            logger.warning(String.format("can't find jenkinsBinding in namespace [%s].", nsName));
//        }
//
//        return false;
//    }

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
