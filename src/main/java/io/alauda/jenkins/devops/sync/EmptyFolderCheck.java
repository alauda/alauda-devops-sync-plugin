package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import jenkins.model.Jenkins;

import java.io.IOException;
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

        folders.stream().filter(folder -> folder.getItems().size() > 0 && folder.getProperties().stream().anyMatch(
                pro -> (pro instanceof AlaudaFolderProperty) && ((AlaudaFolderProperty) pro).isDirty()
        )).forEach(folder -> {
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
