package io.alauda.jenkins.devops.sync.folder;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.hudson.plugins.folder.computed.FolderComputation;
import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.Items;
import hudson.scheduler.CronTabList;
import hudson.scheduler.Hash;
import hudson.scheduler.RareOrImpossibleDateException;
import hudson.triggers.Messages;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;
import io.alauda.jenkins.devops.sync.util.CronUtils;
import org.kohsuke.stapler.AncestorInPath;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static hudson.Util.fixNull;

public class CronFolderTrigger extends Trigger<ComputedFolder<?>> {
    private static final Logger LOGGER = Logger.getLogger(CronFolderTrigger.class.getName());

    private String crontab;

    private transient long lastTriggered;
    private static final long startup = System.currentTimeMillis();

    @DataBoundConstructor
    public CronFolderTrigger(String crontab) throws ANTLRException {
        super(crontab);
        this.crontab = crontab;
    }

    public String getCrontab() {
        return crontab;
    }

    @Override
    public void run() {
        if (job == null) {
            return;
        }

        job.scheduleBuild(0, new TimerTrigger.TimerTriggerCause());
        if (job == null) {
            return;
        }
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
                updateValidationsForSanity(validations, ctl);
                updateValidationsForNextRun(validations, ctl);
                return FormValidation.aggregate(validations);
            } catch (ANTLRException e) {
                if (value.trim().indexOf('\n')==-1 && value.contains("**"))
                    return FormValidation.error(Messages.TimerTrigger_MissingWhitespace());
                return FormValidation.error(e.getMessage());
            }
        }

        private void updateValidationsForSanity(Collection<FormValidation> validations, CronTabList ctl) {
            String msg = ctl.checkSanity();
            if(msg!=null)  validations.add(FormValidation.warning(msg));
        }

        private void updateValidationsForNextRun(Collection<FormValidation> validations, CronTabList ctl) {
            try {
                Calendar prev = ctl.previous();
                Calendar next = ctl.next();
                if (prev != null && next != null) {
                    DateFormat fmt = DateFormat.getDateTimeInstance(DateFormat.FULL, DateFormat.FULL);
                    validations.add(FormValidation.ok(Messages.TimerTrigger_would_last_have_run_at_would_next_run_at(fmt.format(prev.getTime()), fmt.format(next.getTime()))));
                } else {
                    validations.add(FormValidation.warning(Messages.TimerTrigger_no_schedules_so_will_never_run()));
                }
            } catch (RareOrImpossibleDateException ex) {
                validations.add(FormValidation.warning(Messages.TimerTrigger_the_specified_cron_tab_is_rare_or_impossible()));
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
}
