package io.alauda.jenkins.devops.sync.action;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.Queue;
import org.kohsuke.stapler.export.ExportedBean;

import javax.annotation.CheckForNull;
import java.util.List;

/**
 * Always should schedule the job which triggered by alauda devops platfrom
 */
@ExportedBean
public class AlaudaQueueAction implements Queue.QueueAction {
    @Override
    public boolean shouldSchedule(List<Action> list) {
        return true;
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return "alaudaAction";
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return "alaudaAction";
    }
}
