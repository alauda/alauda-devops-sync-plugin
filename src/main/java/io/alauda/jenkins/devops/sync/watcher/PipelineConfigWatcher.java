/*
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
package io.alauda.jenkins.devops.sync.watcher;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.model.TopLevelItem;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigConvert;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.util.*;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Watcher;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link PipelineConfig} objects in Alauda DevOps and for WorkflowJobs we
 * ensure there is a suitable Jenkins Job object defined with the correct
 * configuration
 */
@Extension
public class PipelineConfigWatcher extends AbstractWatcher implements BaseWatcher {
    private final Logger logger = Logger.getLogger(getClass().getName());

    // for coordinating between ItemListener.onUpdate and onDeleted both
    // getting called when we delete a job; ID should be combo of namespace
    // and name for BC to properly differentiate; we don't use UUID since
    // when we filter on the ItemListener side the UUID may not be
    // available
    private static final HashSet<String> deletesInProgress = new HashSet<String>();
    private WatcherCallback watcherCallback;

    public static synchronized void deleteInProgress(String pcName) {
        deletesInProgress.add(pcName);
    }

    public static synchronized boolean isDeleteInProgress(String pcID) {
        return deletesInProgress.contains(pcID);
    }

    public static synchronized void deleteCompleted(String pcID) {
        deletesInProgress.remove(pcID);
    }

    @Override
    public void watch() {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if (client == null) {
            stop();
            logger.severe("client is null, when watch Secret");
            return;
        }

        PipelineConfigList list = client.pipelineConfigs().inAnyNamespace().list();
        String ver = "0";
        if (list != null) {
            ver = list.getMetadata().getResourceVersion();
        }

        watcherCallback = new WatcherCallback<>(this, null);
        setWatcher(client.pipelineConfigs().inAnyNamespace()
                .withResourceVersion(ver).watch(watcherCallback));
    }

    @Override
    public WatcherCallback getWatcherCallback() {
        return watcherCallback;
    }

    @Override
    public void init(String[] namespaces) {
        PipelineConfigToJobMap.initializePipelineConfigToJobMap();

        for (String namespace : namespaces) {
            logger.info("Looking for pipeline configs in namespace " + namespace);
            PipelineConfigList pipelineConfigs = null;
            try {
                pipelineConfigs = AlaudaUtils.getAuthenticatedAlaudaClient()
                        .pipelineConfigs().inNamespace(namespace).list();
                onInitialPipelineConfigs(pipelineConfigs);
            } catch (Exception e) {
                logger.log(SEVERE, "Failed to load PipelineConfigs: " + e, e);
            }
        }
    }

    private synchronized void onInitialPipelineConfigs(PipelineConfigList pipelineConfigs) {
        if (pipelineConfigs == null) {
            return;
        }

        List<PipelineConfig> items = pipelineConfigs.getItems();
        if (items != null) {
            for (PipelineConfig pipelineConfig : items) {
                try {
                    if (!ResourcesCache.getInstance().isBinding(pipelineConfig)) {
                        continue;
                    }

                    upsertJob(pipelineConfig);
                } catch (Exception e) {
                    logger.log(SEVERE, "Failed to update job", e);
                }
            }
        }
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    public synchronized void eventReceived(Watcher.Action action, PipelineConfig pipelineConfig) {
        ObjectMeta meta = pipelineConfig.getMetadata();
        String pipelineName = meta.getName();
        logger.fine("PipelineConfigWatcher receive event: " + action + "; name: " + pipelineName);

        boolean bindingToCurrentJenkins = false;
        if (action == Watcher.Action.DELETED) {
            TopLevelItem item = PipelineConfigToJobMap.getItemByPC(pipelineConfig);
            if (item != null) {
                AlaudaJobProperty pro = PipelineConfigToJobMap.getProperty(item);
                if (pro != null) {
                    bindingToCurrentJenkins = meta.getUid().equals(pro.getUid());
                }
            }
        } else {
            bindingToCurrentJenkins = ResourcesCache.getInstance().isBinding(pipelineConfig);
        }

        if (!bindingToCurrentJenkins) {
            String pipelineBinding = pipelineConfig.getSpec().getJenkinsBinding().getName();
            String jenkinsService = ResourcesCache.getInstance().getJenkinsService();

            String msg = String.format("%s[%s] is not binding to current jenkins[%s]",
                    pipelineName, pipelineBinding, jenkinsService);
            logger.warning(msg);
            return;
        }

        try {
            switch (action) {
                case ADDED:
                    upsertJob(pipelineConfig);
                    break;
                case DELETED:
                    deleteEventToJenkinsJob(pipelineConfig);
                    break;
                case MODIFIED:
                    modifyEventToJenkinsJob(pipelineConfig);
                    break;
                case ERROR:
                    logger.warning("watch for PipelineConfig " + meta.getName() + " received error event ");
                    break;
                default:
                    logger.warning("watch for PipelineConfig " + meta.getName() + " received unknown event " + action);
                    break;
            }
            // we employ impersonation here to insure we have "full access";
            // for example, can we actually
            // read in jobs defs for verification? without impersonation here
            // we would get null back when trying to read in the job from disk
            ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                @Override
                public Void call() throws Exception {
                    // if bc event came after build events, let's
                    // poke the PipelineWatcher builds with no BC list to
                    // create job
                    // runs
                    // TODO: Change to PipelineWatcher
                    PipelineWatcher.flushPipelinesWithNoPCList();
                    // now, if the build event was lost and never
                    // received, builds
                    // will stay in
                    // new for 5 minutes ... let's launch a background
                    // thread to
                    // clean them up
                    // at a quicker interval than the default 5 minute
                    // general build
                    // relist function
                    if (action == Watcher.Action.ADDED) {
                        Runnable backupBuildQuery = new SafeTimerTask() {
                            @Override
                            public void doRun() {
//                if (!CredentialsUtils.hasCredentials()) {
//                  logger.fine("No Alauda Kubernetes Token credential defined.");
//                  return;
//                }
                                // TODO: Change to PipelineList and filter
                                PipelineList pipelineList = JenkinsUtils.filterNew(AlaudaUtils.getAuthenticatedAlaudaClient().pipelines().inNamespace(pipelineConfig.getMetadata().getNamespace())
                                        .withLabel(Constants.ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG, meta.getName()).list());
                                if (pipelineList.getItems().size() > 0) {
                                    logger.fine("pipeline backup query for " + meta.getName() + " found new pipelines");
                                    PipelineWatcher.onInitialPipelines(pipelineList);
                                }
                            }
                        };
                        Timer.get().schedule(backupBuildQuery, 10 * 1000, TimeUnit.MILLISECONDS);
                    }
                    return null;
                }
            });
        } catch (Exception e) {
            logger.log(Level.WARNING, "Caught: " + e, e);
        }
    }

    public <T> void eventReceived(Watcher.Action action, T resource) {
        PipelineConfig pc = (PipelineConfig) resource;
        eventReceived(action, pc);
    }

    /**
     * Update or create PipelineConfig
     * @param pipelineConfig PipelineConfig
     * @throws Exception in case of io error
     */
    private void upsertJob(final PipelineConfig pipelineConfig) throws Exception {
        PipelineConfigStatus pipelineConfigStatus = pipelineConfig.getStatus();
        String pipelineConfigPhase = null;
        if (pipelineConfigStatus == null || !PipelineConfigPhase.SYNCING.equals(
                (pipelineConfigPhase = pipelineConfig.getStatus().getPhase()))) {
            logger.fine(String.format("Do nothing, PipelineConfig [%s], phase [%s].",
                    pipelineConfig.getMetadata().getName(), pipelineConfigPhase));
            return;
        }

        // clean conditions first, any error info will be put it into conditions
        List<Condition> conditions = new ArrayList<>();
        pipelineConfig.getStatus().setConditions(conditions);

        // check plugin dependency
        PipelineConfigUtils.dependencyCheck(pipelineConfig, conditions);

        if (AlaudaUtils.isPipelineStrategyPipelineConfig(pipelineConfig)) {
            // sync on intern of name should guarantee sync on same actual obj
            synchronized (pipelineConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() {
                        ExtensionList<PipelineConfigConvert> convertList = Jenkins.getInstance().getExtensionList(PipelineConfigConvert.class);
                        Optional<PipelineConfigConvert> optional = convertList.stream().filter(convert -> convert.accept(pipelineConfig)).findFirst();
                        if(optional.isPresent()) {
                            PipelineConfigConvert convert = optional.get();

                            try {
                                convert.convert(pipelineConfig);
                            } catch (Exception e) {
                                Condition condition = new Condition();
                                condition.setReason(ErrorMessages.FAIL_TO_CREATE);
                                condition.setMessage(e.getMessage());
                                pipelineConfig.getStatus().getConditions().add(condition);

                                convert.updatePipelineConfigPhase(pipelineConfig);
                                e.printStackTrace();
                            }
                        } else {
                            logger.warning("Can't handle this kind of PipelineConfig." + NamespaceName.create(pipelineConfig));
                        }

                        return null;
                    }
                });
            }
        }
    }

    private synchronized void modifyEventToJenkinsJob(PipelineConfig pipelineConfig) throws Exception {
        if (AlaudaUtils.isPipelineStrategyPipelineConfig(pipelineConfig)) {
            upsertJob(pipelineConfig);
        }
    }

    // innerDeleteEventToJenkinsJob is the actual delete logic at the heart of
    // deleteEventToJenkinsJob
    // that is either in a sync block or not based on the presence of a BC uid
    private void innerDeleteEventToJenkinsJob(final PipelineConfig pipelineConfig) throws Exception {
        final TopLevelItem item = PipelineConfigToJobMap.getItemByPC(pipelineConfig);
        if (item != null) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            synchronized (pipelineConfig.getMetadata().getUid().intern()) {
                ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
                    @Override
                    public Void call() throws Exception {
                        final String pcId = pipelineConfig.getMetadata().getNamespace() + pipelineConfig.getMetadata().getName();
                        try {
                            deleteInProgress(pcId);
                            item.delete();
                        } finally {
                            PipelineConfigToJobMap.removeJobWithPipelineConfig(pipelineConfig);
                            Jenkins.getInstance().rebuildDependencyGraphAsync();
                            deleteCompleted(pcId);
                        }
                        return null;
                    }
                });
                // if the bc has a source secret it is possible it should
                // be deleted as well (called function will cross reference
                // with secret watch)
                CredentialsUtils.deleteSourceCredentials(pipelineConfig);
            }
        }
    }

    // in response to receiving an alauda delete build config event, this
    // method will drive
    // the clean up of the Jenkins job the build config is mapped one to one
    // with; as part of that
    // clean up it will synchronize with the build event watcher to handle build
    // config
    // delete events and build delete events that arrive concurrently and in a
    // nondeterministic
    // order
    private synchronized void deleteEventToJenkinsJob(final PipelineConfig pipelineConfig) throws Exception {
        String pcUid = pipelineConfig.getMetadata().getUid();
        if (pcUid != null && pcUid.length() > 0) {
            // employ intern of the BC UID to facilitate sync'ing on the same
            // actual object
            pcUid = pcUid.intern();
            synchronized (pcUid) {
                innerDeleteEventToJenkinsJob(pipelineConfig);
                return;
            }
        }
        // uid should not be null / empty, but just in case, still clean up
        innerDeleteEventToJenkinsJob(pipelineConfig);
    }

    @Override
    public final String getName() {
        return "PipelineConfigWatcher";
    }
}
