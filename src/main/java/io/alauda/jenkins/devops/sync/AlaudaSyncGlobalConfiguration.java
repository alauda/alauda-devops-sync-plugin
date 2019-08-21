/*
 * Copyright (C) 2018 Alauda.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.util.FormValidation;
import io.alauda.jenkins.devops.sync.controller.ResourceSyncManager;
import jenkins.model.GlobalConfiguration;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;

/**
 * @author suren
 */
@Extension(ordinal = 100)
@Symbol("alaudaSync")
public class AlaudaSyncGlobalConfiguration extends GlobalConfiguration {
    private boolean enabled = true;
    private String jenkinsService;
    private int resyncPeriod = 0;
    private transient String errorMsg;
    private String jobNamePattern;
    private String skipOrganizationPrefix;
    private String skipBranchSuffix;

    public AlaudaSyncGlobalConfiguration() {
        this.load();
    }

    public static AlaudaSyncGlobalConfiguration get() {
        return GlobalConfiguration.all().get(AlaudaSyncGlobalConfiguration.class);
    }

    @Override
    @Nonnull
    public String getDisplayName() {
        return "Alauda Jenkins Sync";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) {
        req.bindJSON(this, json);
        this.save();
        return true;
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getJenkinsService() {
        return this.jenkinsService;
    }

    @DataBoundSetter
    public void setJenkinsService(String jenkinsService) {
        if (jenkinsService != null) {
            jenkinsService = jenkinsService.trim();
        }
        if (this.jenkinsService != null && this.jenkinsService.equals(jenkinsService)) {
            return;
        }

        this.jenkinsService = jenkinsService;
        ResourceSyncManager.getSyncManager().notifyJenkinsServiceChanged();
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    @DataBoundSetter
    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public String getJobNamePattern() {
        return this.jobNamePattern;
    }

    @DataBoundSetter
    public void setJobNamePattern(String jobNamePattern) {
        this.jobNamePattern = jobNamePattern;
    }

    public String getSkipOrganizationPrefix() {
        return this.skipOrganizationPrefix;
    }

    @DataBoundSetter
    public void setSkipOrganizationPrefix(String skipOrganizationPrefix) {
        this.skipOrganizationPrefix = skipOrganizationPrefix;
    }

    public String getSkipBranchSuffix() {
        return this.skipBranchSuffix;
    }

    @DataBoundSetter
    public void setSkipBranchSuffix(String skipBranchSuffix) {
        this.skipBranchSuffix = skipBranchSuffix;
    }


    public int getResyncPeriod() {
        return resyncPeriod;
    }

    @DataBoundSetter
    public void setResyncPeriod(int resyncPeriod) {
        this.resyncPeriod = resyncPeriod;
    }

    public FormValidation doCheckResyncPeriod(@QueryParameter String value) {
        try {
            int minute = Integer.parseInt(value);
            if (minute < 0) {
                return FormValidation.error("Should be greater than or equal to 0");
            }
            return FormValidation.ok();
        } catch (NumberFormatException e) {
            return FormValidation.error("Not a number");
        }
    }

}
