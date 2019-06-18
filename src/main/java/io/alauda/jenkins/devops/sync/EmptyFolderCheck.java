package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import io.alauda.jenkins.devops.sync.controller.JenkinsBindingController;
import jenkins.model.Jenkins;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class EmptyFolderCheck extends AsyncPeriodicWork {
    private static final Logger logger = Logger.getLogger(EmptyFolderCheck.class.getName());

    public EmptyFolderCheck() {
        super("EmptyFolderCheck");
    }

    private List<Folder> folders = null;

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
        try {
            folders = Jenkins.getInstance().getItems(Folder.class);

            if (folders == null) {
                return;
            }

            JenkinsBindingController controller = JenkinsBindingController.getCurrentJenkinsBindingController();
            if (!controller.isValid()) {
                logger.log(Level.INFO, "JenkinsBidingController is not synced or is not valid, will skip this empty folder check");
                return;
            }
            List<String> allNamespaces = controller.getBindingNamespaces();

            // when the folder is dirty and there is not any custom itemJenkinsPipelineJobListener
            folders.stream().filter(folder -> folder.getProperties().stream().anyMatch(
                    pro -> {
                        String folderName = folder.getName();
                        // delay to remove folder
                        // target namespace doesn't exists anymore
                        return pro instanceof AlaudaFolderProperty && (((AlaudaFolderProperty) pro).isDirty()
                                || noneMatch(allNamespaces, folderName) ||
                                noJenkinsBinding(allNamespaces, folderName));  // namespaces exists but no binding
                    }
            )).filter(folder -> {
                Collection<TopLevelItem> items = folder.getItems();
                if (items.size() == 0) {
                    return true;
                }

                // find custom created item
                return items.stream().noneMatch(item -> {
                    if (item instanceof WorkflowJob) {
                        return ((WorkflowJob) item).getProperty(WorkflowJobProperty.class) == null;
                    }
                    return false;
                });
            }).forEach(folder -> {
                try {
                    folder.delete();
                } catch (IOException | InterruptedException e) {
                    e.printStackTrace();
                }
            });
        } finally {
            SecurityContextHolder.setContext(previousContext);
        }
    }

    private boolean noJenkinsBinding(List<String> namespaces, String target) {
        return !namespaces.contains(target);
    }

    private boolean noneMatch(List<String> list, String target) {
        return list.stream().noneMatch(ns -> ns.equalsIgnoreCase(target));
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(10);
    }
}
