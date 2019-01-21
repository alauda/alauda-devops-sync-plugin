package io.alauda.jenkins.devops.sync.folder;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Items;
import hudson.scheduler.CronTabList;
import hudson.scheduler.Hash;
import hudson.triggers.Messages;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.util.ArrayList;
import java.util.Collection;
import java.util.logging.Logger;

import static hudson.Util.fixNull;

public class CronFolderTrigger extends Trigger<ComputedFolder<?>> {
    private static final Logger LOGGER = Logger.getLogger(CronFolderTrigger.class.getName());

    private String crontab;
    // TODO it doesn't support remove a trigger
    // pull request is here https://github.com/jenkinsci/cloudbees-folder-plugin/pull/126
    private boolean enabled;

    @DataBoundConstructor
    public CronFolderTrigger(String crontab, boolean enabled) throws ANTLRException {
        super(crontab);
        this.crontab = crontab;
        this.enabled = enabled;
    }

    @Override
    public void run() {
        if (job == null || !enabled) {
            LOGGER.info("Job is null or current trigger is disabled.");
            return;
        }

        job.scheduleBuild(0, new TimerTrigger.TimerTriggerCause());
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
         * Performs syntax check.
         */
        public FormValidation doCheckCrontab(@QueryParameter String value, @AncestorInPath Item item) {
            try {
                CronTabList ctl = CronTabList.create(fixNull(value), item != null ? Hash.from(item.getFullName()) : null);
                Collection<FormValidation> validations = new ArrayList<>();
                return FormValidation.aggregate(validations);
            } catch (ANTLRException e) {
                if (value.trim().indexOf('\n')==-1 && value.contains("**"))
                    return FormValidation.error(Messages.TimerTrigger_MissingWhitespace());
                return FormValidation.error(e.getMessage());
            }
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

    public boolean isEnabled() {
        return enabled;
    }

    public String getCrontab() {
        return crontab;
    }
}
