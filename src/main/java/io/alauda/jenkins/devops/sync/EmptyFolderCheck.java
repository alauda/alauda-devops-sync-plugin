package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.model.TopLevelItem;
import jenkins.model.Jenkins;
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

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        List<Folder> folders = Jenkins.getInstance().getItems(Folder.class);
        if(folders == null) {
            return;
        }

        // when the folder is dirty and there is not any custom itemJenkinsPipelineJobListener
        folders.stream().filter(folder -> folder.getProperties().stream().anyMatch(
                pro -> (pro instanceof AlaudaFolderProperty) && ((AlaudaFolderProperty) pro).isDirty()
        )).filter(folder -> {
            Collection<TopLevelItem> items = folder.getItems();
            if(items.size() == 0) {
                return true;
            }

            // find custom created item
            return items.stream().noneMatch(item -> {
                if(item instanceof WorkflowJob) {
                    return ((WorkflowJob) item).getProperty(PipelineConfigProjectProperty.class) == null;
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
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(10);
    }
}
