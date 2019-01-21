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
package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.Item;
import hudson.model.listeners.ItemListener;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.*;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.kohsuke.stapler.DataBoundConstructor;

import java.util.Arrays;
import java.util.logging.Logger;

/**
 * Listens to {@link WorkflowJob} objects being updated via the web console or
 * Jenkins REST API and replicating the changes back to the Alauda DevOps
 * {@link PipelineConfig} for the case where folks edit inline Jenkinsfile flows
 * inside the Jenkins UI
 */
@Extension
public class JenkinsPipelineJobListener extends ItemListener {
    private static final Logger logger = Logger.getLogger(JenkinsPipelineJobListener.class.getName());
    private final ExtensionList<ItemEventHandler> handler;

    private String server;
    private String[] namespaces;
    private String jenkinsService;
    private String jobNamePattern;

    @DataBoundConstructor
    public JenkinsPipelineJobListener() {
        handler = Jenkins.getInstance().getExtensionList(ItemEventHandler.class);
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        this.server = config.getServer();
        this.jenkinsService = config.getJenkinsService();
        this.jobNamePattern = config.getJobNamePattern();
        init();
    }

    private void init() {
        AlaudaDevOpsClient client = AlaudaUtils.getAlaudaClient();
        if(client == null) {
            logger.severe("Can't get AlaudaDevOpsClient when init JenkinsPipelineJobListener.");
            return;
        }
        namespaces = AlaudaUtils.getNamespaceOrUseDefault(jenkinsService, client);
    }

    @Override
    public void onCreated(Item item) {
        if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
            return;
        }

        logger.info(String.format("created item: %s", item.getFullName()));

        handler.stream().
                filter(handler -> handler.accept(item)).
                forEach(handler -> handler.onCreated(item));
        reconfigure();
    }

    @Override
    public void onUpdated(Item item) {
        if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
            return;
        }

        logger.info(String.format("updated item: %s", item.getFullName()));

        handler.stream().
                filter(handler -> handler.accept(item)).
                forEach(handler -> handler.onUpdated(item));
        reconfigure();
    }

    @Override
    public void onDeleted(Item item) {
        if (!AlaudaSyncGlobalConfiguration.get().isEnabled()) {
            logger.info("no configuration... onDelete ignored...");
            return;
        }

        logger.info(String.format("deleted Item: %s", item.getFullName()));

        handler.stream().
                filter(handler -> handler.accept(item)).
                forEach(handler -> handler.onDeleted(item));
        reconfigure();
    }

    /**
     * TODO is there a cleaner way to get this class injected with any new
     * configuration from GlobalPluginConfiguration?
     */
    private void reconfigure() {
        AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
        if (config != null) {
            this.jobNamePattern = config.getJobNamePattern();
            this.jenkinsService = config.getJenkinsService();
            this.server = config.getServer();
            init();
        }
    }

    @Override
    public String toString() {
        return "JenkinsPipelineJobListener{" + "server='" + server + '\'' + ", jenkinsService='" +
                jenkinsService + '\'' + ", namespace='" + Arrays.toString(namespaces) + '\'' + ", jobNamePattern='" +
                jobNamePattern + '\'' + '}';
    }
}
