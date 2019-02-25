/**
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

import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.Util;
import hudson.init.InitMilestone;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import io.alauda.jenkins.devops.sync.action.KubernetesClientAction;
import io.alauda.jenkins.devops.sync.credential.AlaudaToken;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.watcher.*;
import io.alauda.kubernetes.client.KubernetesClientException;
import jenkins.model.GlobalConfiguration;
import jenkins.model.GlobalConfigurationCategory;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;

import javax.annotation.Nonnull;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author suren
 */
@Extension(ordinal = 100)
@Symbol("alaudaSync")
public class AlaudaSyncGlobalConfiguration extends GlobalConfiguration {
    private static final Logger LOGGER = Logger.getLogger(AlaudaSyncGlobalConfiguration.class.getName());
    private boolean enabled = true;
    private boolean trustCerts = false;
    private String server;
    private String credentialsId = "";
    private String jenkinsService;
    private String jobNamePattern;
    private String skipOrganizationPrefix;
    private String skipBranchSuffix;
    private String sharedNamespace;
    private int watcherAliveCheck = 5;

    private String[] namespaces;
    private transient PipelineWatcher pipelineWatcher;
    private transient PipelineConfigWatcher pipelineConfigWatcher;
    private transient SecretWatcher secretWatcher;
    private transient JenkinsBindingWatcher jenkinsBindingWatcher;
    private transient NamespaceWatcher namespaceWatcher;

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

    public boolean isValid() {
        return isEnabled() && !"".equals(getJenkinsService());
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isTrustCerts() {
        return trustCerts;
    }

    @DataBoundSetter
    public void setTrustCerts(boolean trustCerts) {
        this.trustCerts = trustCerts;
    }

    @DataBoundSetter
    public void setServer(String server) {
        this.server = server;
    }

    public String getServer() {
        return this.server;
    }

    public String getCredentialsId() {
        return this.credentialsId == null ? "" : this.credentialsId;
    }

    @DataBoundSetter
    public void setCredentialsId(String credentialsId) {
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    }

    public String getJenkinsService() {
        return this.jenkinsService;
    }

    @DataBoundSetter
    public void setJenkinsService(String jenkinsService) {
        this.jenkinsService = jenkinsService != null ? jenkinsService.trim() : null;
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

    public String getSharedNamespace() {
        return sharedNamespace;
    }

    @DataBoundSetter
    public void setSharedNamespace(String sharedNamespace) {
        this.sharedNamespace = sharedNamespace;
    }

    public int getWatcherAliveCheck() {
        return watcherAliveCheck;
    }

    @DataBoundSetter
    public void setWatcherAliveCheck(int watcherAliveCheck) {
        this.watcherAliveCheck = watcherAliveCheck;
    }

    @Nonnull
    public String[] getNamespaces() {
        if(namespaces == null) {
            return new String[]{};
        }
        return Arrays.copyOf(namespaces, namespaces.length);
    }

    @SuppressWarnings("unused")
    public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
        Jenkins jenkins = Jenkins.getInstance();
        return !jenkins.hasPermission(Jenkins.ADMINISTER) ? (new StandardListBoxModel()).includeCurrentValue(credentialsId) : (new StandardListBoxModel()).includeEmptyValue().includeAs(ACL.SYSTEM, jenkins, AlaudaToken.class).includeCurrentValue(credentialsId);
    }

    @SuppressWarnings("unused")
    public FormValidation doVerifyConnect(@QueryParameter String server,
                                          @QueryParameter String credentialsId,
                                          @QueryParameter Boolean trustCerts) {
        try {
            URL url = new KubernetesClientAction().connectTest(server, credentialsId, trustCerts);

            return FormValidation.ok(String.format("Connect to %s success.", url.toString()));
        } catch(KubernetesClientException e) {
            return FormValidation.error(e, "Failed to connect kubernetes");
        }
    }

    public void reloadNamespaces() {
        this.namespaces = AlaudaUtils.getNamespaceOrUseDefault(this.jenkinsService, AlaudaUtils.getAuthenticatedAlaudaClient());

        for (String namespace : namespaces) {
            ResourcesCache.getInstance().addNamespace(namespace);
        }
    }

    /***
     * Just for re-watch all the namespaces
     */
    public void reWatchAllNamespace(String namespace) {
        stopWatchers();

        reloadNamespaces();

        // put the new guy at first
        List<String> namespaceList = new ArrayList<>();
        namespaceList.add(namespace);
        for(String old : namespaces) {
            if(old.equals(namespace)) {
                continue;
            }

            namespaceList.add(old);
        }
        namespaces = namespaceList.toArray(new String[]{});

        startWatchers();
    }

    /**
    * Only call when the plugin configuration is really changed.
    */
    public void configChange() throws KubernetesClientException {
        ResourcesCache.getInstance().setJenkinsService(jenkinsService);

        if (!this.enabled || StringUtils.isBlank(jenkinsService)) {
            this.stopWatchersAndClient();
            LOGGER.warning("Plugin is disabled, all watchers will be stoped.");
            return;
        }

        ResourcesCache.getInstance().setJenkinsService(jenkinsService);

        try {
            stopWatchersAndClient();

            if(AlaudaUtils.getAlaudaClient() == null) {
                AlaudaUtils.initializeAlaudaDevOpsClient(this.server);
            }

            reloadNamespaces();

            Runnable task = new SafeTimerTask() {
                protected void doRun() throws Exception {
                    LOGGER.info("Waiting for Jenkins to be started");

                    while(true) {
                        Jenkins instance = Jenkins.getInstance();
                        InitMilestone initLevel = instance.getInitLevel();
                        LOGGER.fine("Jenkins init level: " + initLevel.toString());
                        if (initLevel == InitMilestone.COMPLETED) {
                            startWatchers();
                            return;
                        }

                        LOGGER.fine("Jenkins not ready...");

                        Thread.sleep(500L);
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

            throw e;
        }
    }

    public void startWatchers() {
        this.jenkinsBindingWatcher = new JenkinsBindingWatcher();
        this.jenkinsBindingWatcher.watch();

        this.pipelineWatcher = new PipelineWatcher();
        this.pipelineWatcher.watch();
        this.pipelineWatcher.init(namespaces);

        this.pipelineConfigWatcher = new PipelineConfigWatcher();
        this.pipelineConfigWatcher.watch();
        this.pipelineConfigWatcher.init(namespaces);

        this.secretWatcher = new SecretWatcher();
        this.secretWatcher.watch();
        this.secretWatcher.init(namespaces);

        namespaceWatcher = new NamespaceWatcher();
        namespaceWatcher.watch();
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

        if(namespaceWatcher != null) {
            namespaceWatcher.stop();
            namespaceWatcher = null;
        }
    }

    public PipelineWatcher getPipelineWatcher() {
        return pipelineWatcher;
    }

    public PipelineConfigWatcher getPipelineConfigWatcher() {
        return pipelineConfigWatcher;
    }

    public SecretWatcher getSecretWatcher() {
        return secretWatcher;
    }

    public JenkinsBindingWatcher getJenkinsBindingWatcher() {
        return jenkinsBindingWatcher;
    }

    public void setPipelineWatcher(PipelineWatcher pipelineWatcher) {
        this.pipelineWatcher = pipelineWatcher;
    }

    public void setPipelineConfigWatcher(PipelineConfigWatcher pipelineConfigWatcher) {
        this.pipelineConfigWatcher = pipelineConfigWatcher;
    }

    public void setSecretWatcher(SecretWatcher secretWatcher) {
        this.secretWatcher = secretWatcher;
    }

    public void setJenkinsBindingWatcher(JenkinsBindingWatcher jenkinsBindingWatcher) {
        this.jenkinsBindingWatcher = jenkinsBindingWatcher;
    }

    public NamespaceWatcher getNamespaceWatcher() {
        return namespaceWatcher;
    }

    public void stopWatchersAndClient() {
        stopWatchers();

        AlaudaUtils.shutdownAlaudaClient();
    }
}
