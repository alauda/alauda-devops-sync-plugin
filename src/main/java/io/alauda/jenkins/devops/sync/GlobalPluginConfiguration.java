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
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
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
    private String[] namespaces;
    private transient PipelineWatcher pipelineWatcher;
    private transient PipelineConfigWatcher pipelineConfigWatcher;
    private transient SecretWatcher secretWatcher;
    private transient JenkinsBindingWatcher jenkinsBindingWatcher;

    @DataBoundConstructor
    public GlobalPluginConfiguration(boolean enable, String server, String jenkinsService, String credentialsId,
                                     String jobNamePattern, String skipOrganizationPrefix, String skipBranchSuffix) {
        this.enabled = enable;
        this.server = server;
        this.jenkinsService = StringUtils.isBlank(jenkinsService) ? "" : jenkinsService;
        this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
        this.jobNamePattern = jobNamePattern;
        this.skipOrganizationPrefix = skipOrganizationPrefix;
        this.skipBranchSuffix = skipBranchSuffix;

        try {
            this.configChange();
        } catch (KubernetesClientException e) {
            LOGGER.log(Level.SEVERE, "trigger config change failed.", e);
        }
    }

    public GlobalPluginConfiguration() {
        this.load();

        try {
            this.configChange();

            LOGGER.info("Alauda GlobalPluginConfiguration is started.");
        } catch (KubernetesClientException e) {
            LOGGER.log(Level.SEVERE, "trigger config change failed.", e);
        }
    }

    public static GlobalPluginConfiguration get() {
        return GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
    }

    public static boolean isItEnabled() {
        GlobalPluginConfiguration config = get();
        return config != null && config.isEnabled();
    }

    @Override
    @Nonnull
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

    public void setJobNamePattern(String jobNamePattern) {
        this.jobNamePattern = jobNamePattern;
    }

    public String getSkipOrganizationPrefix() {
        return this.skipOrganizationPrefix;
    }

    public void setSkipOrganizationPrefix(String skipOrganizationPrefix) {
        this.skipOrganizationPrefix = skipOrganizationPrefix;
    }

    public String getSkipBranchSuffix() {
        return this.skipBranchSuffix;
    }

    public void setSkipBranchSuffix(String skipBranchSuffix) {
        this.skipBranchSuffix = skipBranchSuffix;
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
        if (jenkins == null) {
            return (ListBoxModel) null;
        } else {
            return !jenkins.hasPermission(Jenkins.ADMINISTER) ? (new StandardListBoxModel()).includeCurrentValue(credentialsId) : (new StandardListBoxModel()).includeEmptyValue().includeAs(ACL.SYSTEM, jenkins, AlaudaToken.class).includeCurrentValue(credentialsId);
        }
    }

    @SuppressWarnings("unused")
    public FormValidation doVerifyConnect(@QueryParameter String server,
                                          @QueryParameter String credentialId) {
        try {
            URL url = new KubernetesClientAction().connectTest(server, credentialId);

            return FormValidation.ok(String.format("Connect to %s success.", url.toString()));
        } catch(KubernetesClientException e) {
            return FormValidation.error(e, "Failed to connect kubernetes");
        }
    }

    public void reloadNamespaces() {
        this.namespaces = AlaudaUtils.getNamespaceOrUseDefault(this.jenkinsService, AlaudaUtils.getAlaudaClient());

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
    } else {
        ResourcesCache.getInstance().setJenkinsService(jenkinsService);

      try {
        stopWatchersAndClient();

        if(AlaudaUtils.getAlaudaClient() == null) {
          AlaudaUtils.initializeAlaudaDevOpsClient(this.server);
        }

        reloadNamespaces();

        Runnable task = new SafeTimerTask() {
          protected void doRun() throws Exception {
            GlobalPluginConfiguration.LOGGER.info("Waiting for Jenkins to be started");

            while(true) {
              Jenkins instance = Jenkins.getInstance();
              InitMilestone initLevel = instance.getInitLevel();
              GlobalPluginConfiguration.LOGGER.fine("Jenkins init level: " + initLevel.toString());
              if (initLevel == InitMilestone.COMPLETED) {
                startWatchers();
                return;
              }

              GlobalPluginConfiguration.LOGGER.fine("Jenkins not ready...");

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
