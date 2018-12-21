package io.alauda.jenkins.devops.sync.folder;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Items;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class CronFolderTrigger extends Trigger<ComputedFolder<?>> {
    private String crontab;

    @DataBoundConstructor
    public CronFolderTrigger(String crontab) throws ANTLRException {
        super(crontab);
        this.crontab = crontab;
    }

    public String getCrontab() {
        return crontab;
    }

    /**
     * Our {@link hudson.model.Descriptor}
     */
    @Extension
    @SuppressWarnings("unused") // instantiated by Jenkins
    public static class DescriptorImpl extends TriggerDescriptor {
        /**
         * {@inheritDoc}
         */
        public boolean isApplicable(Item item) {
            return item instanceof ComputedFolder;
        }

        /**
         * {@inheritDoc}
         */
        @Override
        public String getDisplayName() {
            return "Cron Trigger";
        }

        static {
            Items.XSTREAM2.addCompatibilityAlias("jenkins.branch.IndexAtLeastTrigger", CronFolderTrigger.class);
        }
    }
}
