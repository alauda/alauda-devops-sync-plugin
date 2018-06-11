/**
 * Copyright (C) 2018 Alauda.io
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync;

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.ListBoxModel;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.watcher.*;
import io.alauda.kubernetes.client.KubernetesClientException;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import lombok.Data;
import lombok.NonNull;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author suren
 */
@Extension
public class GlobalPluginConfiguration extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(GlobalPluginConfiguration.class.getName());
    private boolean enabled = true;
    private String server;
    private String credentialsId = "";
    private String jenkinsService;
    private String jobNamePattern;
    private String skipOrganizationPrefix;
    private String skipBranchSuffix;
    private String skipNamespace;
    private String[] namespaces;
    private transient PipelineWatcher pipelineWatcher;
    private transient PipelineConfigWatcher pipelineConfigWatcher;
    private transient SecretWatcher secretWatcher;
    private transient JenkinsBindingWatcher jenkinsBindingWatcher;

    @DataBoundConstructor
    public GlobalPluginConfiguration(boolean enable, String server, String jenkinsService, String credentialsId) {
        this.enabled = enable;
        this.server = server;
        this.jenkinsService = StringUtils.isBlank(jenkinsService) ? "" : jenkinsService;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.configChange();

        ResourcesCache.getInstance().setJenkinsService(jenkinsService);
    }

    public GlobalPluginConfiguration() {
        this.load();
        this.configChange();
        ResourcesCache.getInstance().setJenkinsService(jenkinsService);

        LOGGER.info("Alauda GlobalPluginConfiguration is started.");
    }

    public static GlobalPluginConfiguration get() {
        return (GlobalPluginConfiguration) GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
    }

    public static boolean isItEnabled() {
        GlobalPluginConfiguration config = get();
        return config != null && config.isEnabled();
    }

    @Nonnull
    @Override
    public String getDisplayName() {
        return "Alauda Jenkins Sync";
    }

    @Override
    public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
        req.bindJSON(this, json);

        try {
            this.configChange();

            this.save();
            return true;
        } catch (KubernetesClientException e) {
            return false;
        }
    }

    public boolean isEnabled() {
        return this.enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getServer() {
        return this.server;
    }

    public void setServer(String server) {
        this.server = server;
    }

    public String getCredentialsId() {
        return this.credentialsId == null ? "" : this.credentialsId;
    }

    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getJenkinsService() {
        return this.jenkinsService;
    }

    public void setJenkinsService(String jenkinsService) {
        this.jenkinsService = jenkinsService;
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

    public String getSkipNamespace() {
        return skipNamespace;
    }

    @DataBoundSetter
    public void setSkipNamespace(String skipNamespace) {
        this.skipNamespace = skipNamespace;
    }

    public String[] getNamespaces() {
        return namespaces;
    }

    public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
        Jenkins jenkins = Jenkins.getInstance();
        if(jenkins.hasPermission(Jenkins.ADMINISTER)) {
           return (new StandardListBoxModel()).includeCurrentValue(credentialsId);
        } else {
            return (new StandardListBoxModel()).includeEmptyValue().includeAs(ACL.SYSTEM, jenkins, AlaudaToken.class)
                    .includeCurrentValue(credentialsId);
        }
    }

    /**
     * Reload all namespace belong to current jenkins, and will skip some of them configured by users.
     */
    public void reloadNamespaces() {
        String[] allNamespaces = AlaudaUtils.getNamespaceOrUseDefault(this.jenkinsService, AlaudaUtils.getAlaudaClient());

        String[] skipNamespaceArray = new String[]{};
        if (skipNamespace != null) {
            skipNamespaceArray = skipNamespace.split(",");
        }

        ResourcesCache resourcesCache = ResourcesCache.getInstance();
        for (String namespace : allNamespaces) {
            boolean needSkip = false;
            for (String skip : skipNamespaceArray) {
                if (namespace.equals(skip)) {
                    needSkip = true;
                    break;
                }
            }

            if (needSkip) {
                continue;
            }

            resourcesCache.addNamespace(namespace);
        }

        this.namespaces = resourcesCache.getNamespace();
    }

    /***
     * Just for re-watch all the namespaces
     */
    public void reWatchAllNamespace(String namespace) {
        stopWatchers();

        reloadNamespaces();

        // put the new guy at first
        List<String> namespaceList = Arrays.asList(namespaces);
        namespaceList.remove(namespace);
        namespaceList.add(0, namespace);
        namespaces = namespaceList.toArray(new String[]{});

        startWatchers();
    }

    /**
     * Only call when the plugin configuration is really changed.
     * Do nothing when KubernetesClientException occurs
     */
    public void configChange() {
        if (!this.enabled || StringUtils.isBlank(jenkinsService)) {
            this.stopWatchersAndClient();
            LOGGER.warning("Plugin is disabled, all watchers will be stoped.");
        } else {
            try {
                stopWatchersAndClient();

                if (AlaudaUtils.getAlaudaClient() == null) {
                    AlaudaUtils.initializeAlaudaDevOpsClient(this.server);
                }

                reloadNamespaces();

                Runnable task = new SafeTimerTask() {
                    protected void doRun() throws Exception {
                        GlobalPluginConfiguration.LOGGER.info("Waiting for Jenkins to be started");

                        while (true) {
                            Jenkins instance = Jenkins.getInstance();
                            InitMilestone initLevel = instance.getInitLevel();
                            GlobalPluginConfiguration.LOGGER.fine("Jenkins init level: " + initLevel.toString());
                            if (initLevel == InitMilestone.COMPLETED) {
                                startWatchers();
                                return;
                            }

                            GlobalPluginConfiguration.LOGGER.fine("Jenkins not ready...");

                            try {
                                Thread.sleep(500L);
                            } catch (InterruptedException var4) {
                                ;
                            }
                        }
                    }
                };
                Timer.get().schedule(task, 1L, TimeUnit.SECONDS);
            } catch (KubernetesClientException e) {
                if (e.getCause() != null) {
                    LOGGER.log(Level.SEVERE, "Failed to configure Alauda Jenkins Sync Plugin: " + e.getCause());
                } else {
                    LOGGER.log(Level.SEVERE, "Failed to configure Alauda Jenkins Sync Plugin: " + e);
                }
            }
        }
    }

    public void startWatchers() {
        this.jenkinsBindingWatcher = new JenkinsBindingWatcher();
        this.jenkinsBindingWatcher.init(namespaces);

        this.pipelineWatcher = new PipelineWatcher();
        this.pipelineWatcher.init(namespaces);

        this.pipelineConfigWatcher = new PipelineConfigWatcher();
        this.pipelineConfigWatcher.init(namespaces);

        this.secretWatcher = new SecretWatcher();
        this.secretWatcher.init(namespaces);
    }

    public void stopWatchers() {
        if (this.pipelineWatcher != null) {
            this.pipelineWatcher.stop();
            this.pipelineWatcher = null;
        }

        if (this.pipelineConfigWatcher != null) {
            this.pipelineConfigWatcher.stop();
            this.pipelineConfigWatcher = null;
        }

        if (this.secretWatcher != null) {
            this.secretWatcher.stop();
            this.secretWatcher = null;
        }

        if (jenkinsBindingWatcher != null) {
            jenkinsBindingWatcher.stop();
            jenkinsBindingWatcher = null;
        }
    }

    public void stopWatchersAndClient() {
        stopWatchers();

        AlaudaUtils.shutdownAlaudaClient();
    }
}
