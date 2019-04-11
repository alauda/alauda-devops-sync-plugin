package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
import io.alauda.kubernetes.api.model.Namespace;
import io.alauda.kubernetes.api.model.NamespaceList;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Extension
public class EmptyFolderCheck extends AsyncPeriodicWork {
    public EmptyFolderCheck() {
        super("EmptyFolderCheck");
    }

    private List<Folder> folders = null;

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
        try {
            folders = Jenkins.getInstance().getItems(Folder.class);

            if(folders == null) {
                return;
            }

            List<Namespace> allNamespaces = getAllNamespaces();

            // when the folder is dirty and there is not any custom itemJenkinsPipelineJobListener
            folders.stream().filter(folder -> folder.getProperties().stream().anyMatch(
                    pro -> {
                        String folderName = folder.getName();
                        return (pro instanceof AlaudaFolderProperty &&
                                (((AlaudaFolderProperty) pro).isDirty() || // delay to remove folder
                                        (allNamespaces != null && noneMatch(allNamespaces, folderName)) || // target namespace doesn't exists anymore
                                        noJenkinsBinding(folderName)));  // namespaces exists but no binding
                    }
            )).filter(folder -> {
                Collection<TopLevelItem> items = folder.getItems();
                if(items.size() == 0) {
                    return true;
                }

                // find custom created item
                return items.stream().noneMatch(item -> {
                    if(item instanceof WorkflowJob) {
                        return ((WorkflowJob) item).getProperty(WorkflowJobProperty.class) == null;
                    }
                    return false;
                });
            }).forEach(folder -> {
                try {
                    folder.delete();
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private boolean noJenkinsBinding(String namespace) {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return false;
        }

        JenkinsBindingList list = client.jenkinsBindings().inNamespace(namespace).list();
        return list == null || list.getItems().isEmpty();
    }

    private boolean noneMatch(List<Namespace> list, String target) {
        return list.stream().noneMatch(ns -> ns.getMetadata().getName().equalsIgnoreCase(target));
    }

    private List<Namespace> getAllNamespaces() {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return null;
        }

        NamespaceList list = client.namespaces().list();
        if(list != null) {
            return list.getItems();
        }
        return null;
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(10);
    }
}
