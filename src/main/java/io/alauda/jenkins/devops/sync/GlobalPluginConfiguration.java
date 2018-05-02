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
import hudson.util.ListBoxModel;
import io.alauda.kubernetes.client.KubernetesClientException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import jenkins.util.Timer;
import net.sf.json.JSONObject;
import org.apache.commons.lang.StringUtils;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

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
//  private transient JenkinsBindingWatcher jenkinsBindingWatcher;
  private transient NamespaceWatcher namespaceWatcher;

  @DataBoundConstructor
  public GlobalPluginConfiguration(boolean enable, String server, String jenkinsService, String credentialsId, String jobNamePattern, String skipOrganizationPrefix, String skipBranchSuffix) {
    this.enabled = enable;
    this.server = server;
    this.jenkinsService = StringUtils.isBlank(jenkinsService) ? "" : jenkinsService;
    this.credentialsId = Util.fixEmptyAndTrim(credentialsId);
    this.jobNamePattern = jobNamePattern;
    this.skipOrganizationPrefix = skipOrganizationPrefix;
    this.skipBranchSuffix = skipBranchSuffix;
    this.configChange();
  }

  public GlobalPluginConfiguration() {
    this.load();
    this.configChange();

    LOGGER.info("Alauda GlobalPluginConfiguration is started.");
  }

  public static GlobalPluginConfiguration get() {
    return (GlobalPluginConfiguration)GlobalConfiguration.all().get(GlobalPluginConfiguration.class);
  }

  public static boolean isItEnabled() {
    GlobalPluginConfiguration config = get();
    return config != null ? config.isEnabled() : false;
  }

  public String getDisplayName() {
    return "Alauda Jenkins Sync";
  }

  public boolean configure(StaplerRequest req, JSONObject json) throws FormException {
    req.bindJSON(this, json);
    this.configChange();
    this.save();
    return true;
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

  public static ListBoxModel doFillCredentialsIdItems(String credentialsId) {
    Jenkins jenkins = Jenkins.getInstance();
    if (jenkins == null) {
      return (ListBoxModel)null;
    } else {
      return !jenkins.hasPermission(Jenkins.ADMINISTER) ? (new StandardListBoxModel()).includeCurrentValue(credentialsId) : (new StandardListBoxModel()).includeEmptyValue().includeAs(ACL.SYSTEM, jenkins, AlaudaToken.class).includeCurrentValue(credentialsId);
    }
  }

  public void configChange() {
    if (!this.enabled) {
      this.stopWatchersAndClient();
      LOGGER.warning("Plugin is disabled, all watchers will be stoped.");
    } else {
      try {
        AlaudaUtils.initializeAlaudaDevOpsClient(this.server);
        this.namespaces = AlaudaUtils.getNamespaceOrUseDefault(this.jenkinsService, AlaudaUtils.getAlaudaClient());
        Runnable task = new SafeTimerTask() {
          protected void doRun() throws Exception {
            GlobalPluginConfiguration.LOGGER.info("Waiting for Jenkins to be started");

            while(true) {
              Jenkins instance = Jenkins.getActiveInstance();
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
      } catch (KubernetesClientException var2) {
        if (var2.getCause() != null) {
          LOGGER.log(Level.SEVERE, "Failed to configure Alauda Jenkins Sync Plugin: " + var2.getCause());
        } else {
          LOGGER.log(Level.SEVERE, "Failed to configure Alauda Jenkins Sync Plugin: " + var2);
        }
      }
    }
  }

  private void startWatchers() {
    this.pipelineWatcher = new PipelineWatcher(this.namespaces);
    this.pipelineWatcher.start();
    this.pipelineConfigWatcher = new PipelineConfigWatcher(this.namespaces);
    this.pipelineConfigWatcher.start();
    this.secretWatcher = new SecretWatcher(this.namespaces);
    this.secretWatcher.start();

    if(this.namespaceWatcher == null) {
      namespaceWatcher = new NamespaceWatcher(this.namespaces);
      namespaceWatcher.start();
    }
  }

  private void stopWatchersAndClient() {
    if (this.pipelineWatcher != null) {
      this.pipelineWatcher.stop();
    }

    if (this.pipelineConfigWatcher != null) {
      this.pipelineConfigWatcher.stop();
    }

    if (this.secretWatcher != null) {
      this.secretWatcher.stop();
    }

    AlaudaUtils.shutdownAlaudaClient();
  }
}
