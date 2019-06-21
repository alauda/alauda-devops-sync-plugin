package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobPropertyDescriptor;
import hudson.util.VersionNumber;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;


/**
 * 0.2.28
 */
@Deprecated
public class PipelineConfigProjectProperty extends WorkflowJobProperty {
    @DataBoundConstructor
    public PipelineConfigProjectProperty(String namespace, String name,
                                         String uid, String resourceVersion, String contextAnnotation) {
        super(namespace, name, uid, resourceVersion, contextAnnotation );
    }


    @Extension
    @Restricted(NoExternalUse.class)
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        public boolean isApplicable(Class<? extends Job> jobType) {
            AlaudaSyncPlugin plugin = Jenkins.getInstance().getPlugin(AlaudaSyncPlugin.class);
            if(plugin != null) {
                if(plugin.getWrapper().getVersionNumber().isNewerThan(new VersionNumber("0.2.28"))) {
                    return false;
                }
            }

            return WorkflowJob.class.isAssignableFrom(jobType);
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Alauda Pipeline job";
        }
    }
}
