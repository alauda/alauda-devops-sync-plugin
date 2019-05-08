package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.Job;
import hudson.model.JobPropertyDescriptor;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import javax.annotation.Nonnull;
import java.util.Map;


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
    public static final class DescriptorImpl extends JobPropertyDescriptor {
        public boolean isApplicable(Class<? extends Job> jobType) {
            return WorkflowJob.class.isAssignableFrom(jobType);
        }

        @Nonnull
        @Override
        public String getDisplayName() {
            return "Alauda Pipeline job";
        }
    }
}
