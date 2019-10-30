package io.alauda.jenkins.devops.sync.action;

import hudson.model.Action;
import hudson.model.Queue;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import org.kohsuke.stapler.export.ExportedBean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.CheckForNull;
import java.util.List;

@ExportedBean
public class AlaudaQueueAction implements Queue.QueueAction {
    private static final Logger logger = LoggerFactory.getLogger(AlaudaQueueAction.class);

    private NamespaceName mappedPipelineNamespaceName;

    public AlaudaQueueAction(String pipelineNamespace, String pipelineName) {
        this.mappedPipelineNamespaceName = new NamespaceName(pipelineNamespace, pipelineName);
    }

    @Override
    public boolean shouldSchedule(List<Action> actionsOfItemWillBeQueued) {
        for (Action action : actionsOfItemWillBeQueued) {
            if (action instanceof AlaudaQueueAction) {
                // if there is a same pipeline queued, we should not queue it again
                if (((AlaudaQueueAction) action).getMappedPipelineNamespaceName().equals(this.mappedPipelineNamespaceName)) {
                    logger.info("Pipeline '{}/{}' has already been added to build queue, won't schedule a new build.",
                            mappedPipelineNamespaceName.getNamespace(), mappedPipelineNamespaceName.getName());
                    return false;
                }
            }
        }
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

    public NamespaceName getMappedPipelineNamespaceName() {
        return mappedPipelineNamespaceName;
    }
}
