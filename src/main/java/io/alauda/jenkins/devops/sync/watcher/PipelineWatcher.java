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
package io.alauda.jenkins.devops.sync.watcher;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.Extension;
import hudson.security.ACL;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.PipelineNumComparator;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.WatcherCallback;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Watcher;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.SEVERE;

/**
 * @author suren
 */
@Extension
public class PipelineWatcher extends AbstractWatcher implements BaseWatcher {
    private static final Logger logger = Logger.getLogger(PipelineWatcher.class.getName());
    private static final HashSet<Pipeline> pipelinesWithNoPCList = new HashSet<>();
    private WatcherCallback<Pipeline> watcherCallback;

    @Override
    public void watch() {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            stop();
            logger.severe("client is null, when watch Pipeline");
            return;
        }

        PipelineList list = client
                .pipelines().inAnyNamespace().list();
        String ver = "0";
        if(list != null) {
            ver = list.getMetadata().getResourceVersion();
        }

        watcherCallback = new WatcherCallback<Pipeline>(this, null);
        setWatcher(client.pipelines()
                .inAnyNamespace()
                .withResourceVersion(ver)
                .watch(watcherCallback));
    }

    @Override
    public WatcherCallback getWatcherCallback() {
        return watcherCallback;
    }

    @Override
    public void init(String[] namespaces) {
        PipelineConfigToJobMap.initializePipelineConfigToJobMap();
        PipelineWatcher.flushPipelinesWithNoPCList();
        for (String namespace : namespaces) {
            try {
                logger.fine("listing Pipeline resources");

                // TODO: Filter directly in the API
                PipelineList newPipelines = filterNew(AlaudaUtils.getAuthenticatedAlaudaClient()
                        .pipelines()
                        .inNamespace(namespace)
                        .list());

                if(newPipelines == null || newPipelines.getItems() == null
                        || newPipelines.getItems().size() == 0) {
                    continue;
                }

                onInitialPipelines(newPipelines);

                logger.fine("handled Pipeline resources");
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to load initial Builds: " + e, e);
            }
        }

        reconcileRunsAndPipelines();
    }

    private PipelineList filterNew(PipelineList list) {
      return JenkinsUtils.filterNew(list);
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    public synchronized void eventReceived(Watcher.Action action, Pipeline pipeline) {
        String pipelineName = pipeline.getMetadata().getName();

        logger.info(() -> "Pipeline event: " + action + " - pipeline " + pipelineName);

        if (!AlaudaUtils.isPipelineStrategyPipeline(pipeline)) {
            logger.warning(() -> "Pipeline " + pipelineName  + " is not Alauda pipeline strategy.");
            return;
        }

        if(!ResourcesCache.getInstance().isBinding(pipeline)) {
            logger.warning(() -> "Pipeline " + pipelineName + " is not binding to current jenkins " + ResourcesCache.getInstance().getJenkinsService());
            return;
        }

        try {
            switch (action) {
            case ADDED:
                addEventToJenkinsJobRun(pipeline);
                break;
            case MODIFIED:
                modifyEventToJenkinsJobRun(pipeline);
                break;
            case DELETED:
                deleteEventToJenkinsJobRun(pipeline);
                break;
            case ERROR:
                logger.warning("watch for pipeline " + pipelineName + " received error event ");
                break;
            default:
                logger.warning("watch for pipeline " + pipelineName + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(SEVERE, String.format("Caught exception when %s", action), e);
        }
    }

    @Override
    public <T> void eventReceived(Watcher.Action action, T resource) {
        Pipeline pipeline = (Pipeline)resource;
        eventReceived(action, pipeline);
    }

    public synchronized static void onInitialPipelines(PipelineList pipelineList) {
        List<Pipeline> items = pipelineList.getItems();
        Collections.sort(items, new PipelineNumComparator());

        // We need to sort the builds into their build configs so we can
        // handle build run policies correctly.
        Map<String, PipelineConfig> pipelineConfigMap = new HashMap<>();
        Map<PipelineConfig, List<Pipeline>> pipelineConfigBuildMap = new HashMap<>(items.size());
        for (Pipeline pipe : items) {
            if (!AlaudaUtils.isPipelineStrategyPipeline(pipe))
                continue;

            if(!ResourcesCache.getInstance().isBinding(pipe)) {
                continue;
            }

            String pipelineConfigName = pipe.getSpec().getPipelineConfig().getName();
            if (StringUtils.isEmpty(pipelineConfigName)) {
                continue;
            }

            String namespace = pipe.getMetadata().getNamespace();
            String configMapKey = namespace + "/" + pipelineConfigName;
            PipelineConfig pc = pipelineConfigMap.get(configMapKey);
            if (pc == null) {
                pc = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs()
                        .inNamespace(namespace).withName(pipelineConfigName)
                        .get();
                if (pc == null) {
                    // if the pc is not there via a REST get, then it is not
                    // going to be, and we are not handling manual creation
                    // of pipeline objects, so don't bother with "no pc list"
                    continue;
                }
                pipelineConfigMap.put(configMapKey, pc);
            }
            List<Pipeline> pcPipelines = pipelineConfigBuildMap.get(pc);
            if (pcPipelines == null) {
                pcPipelines = new ArrayList<>();
                pipelineConfigBuildMap.put(pc, pcPipelines);
            }
            pcPipelines.add(pipe);
        }

        // Now handle the pipelines.
        for (Map.Entry<PipelineConfig, List<Pipeline>> pipelineConfigPipelines : pipelineConfigBuildMap
                .entrySet()) {
          PipelineConfig pc = pipelineConfigPipelines.getKey();
            if (pc.getMetadata() == null) {
                // Should never happen but let's be safe...
                continue;
            }
            WorkflowJob job = PipelineConfigToJobMap.getJobFromPipelineConfig(pc);
            if (job == null) {
                List<Pipeline> pipelines = pipelineConfigPipelines.getValue();
                for (Pipeline p : pipelines) {
                    logger.info("skipping listed new pipeline "
                            + p.getMetadata().getName()
                            + " no job at this time");
                    addPipelineToNoPCList(p);
                }
                continue;
            }
            WorkflowJobProperty bcp = job
                    .getProperty(WorkflowJobProperty.class);
            if (bcp == null) {
                List<Pipeline> pipelines = pipelineConfigPipelines.getValue();
                for (Pipeline pipe : pipelines) {
                    logger.info("skipping listed new pipeline "
                            + pipe.getMetadata().getName()
                            + " no prop at this time");
                    addPipelineToNoPCList(pipe);
                }
                continue;
            }
            List<Pipeline> pipelines = pipelineConfigPipelines.getValue();
            JenkinsUtils.handlePipelineList(job, pipelines);
        }
    }

    private static void modifyEventToJenkinsJobRun(Pipeline pipeline) {
        PipelineStatus status = pipeline.getStatus();
        logger.info("Modified pipeline "+pipeline.getMetadata().getName());
        if (status != null && AlaudaUtils.isCancellable(status) && AlaudaUtils.isCancelled(status)) {
          logger.info("Pipeline was cancelled "+pipeline.getMetadata().getName());
            WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
            if (job != null) {
                JenkinsUtils.cancelPipeline(job, pipeline);
            } else {
                removePipelineFromNoPCList(pipeline);
            }
        } else {
          logger.info("Pipeline changed... flusing pipelines... "+pipeline.getMetadata().getName());
            // see if any pre-BC cached builds can now be flushed
            flushPipelinesWithNoPCList();
        }
    }

    public static synchronized boolean addEventToJenkinsJobRun(Pipeline pipeline)
            throws IOException {
        // should have been caught upstack, but just in case since public method
        if (!AlaudaUtils.isPipelineStrategyPipeline(pipeline))
            return false;
        PipelineStatus status = pipeline.getStatus();
        if (status != null) {
            logger.info("Pipeline Status is not null: "+status);
            if (AlaudaUtils.isCancelled(status)) {
              logger.info("Pipeline Status is Cancelled... updating pipeline: "+status);
                AlaudaUtils.updatePipelinePhase(pipeline, PipelinePhases.CANCELLED);
                return false;
            }
            if (!AlaudaUtils.isNew(status)) {
              logger.info("Pipeline is not new... cancelling... "+status);
                return false;
            }
        }

        WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
        logger.info("Pipeline got job... "+job);
        if (job != null) {
            logger.info("Pipeline job will trigger... "+job+" pipeline: "+pipeline.getMetadata().getName());
            return JenkinsUtils.triggerJob(job, pipeline);
        }

        logger.info("skipping watch event for pipeline "
                + pipeline.getMetadata().getName() + " no job at this time");
        addPipelineToNoPCList(pipeline);
        return false;
    }

    public static void addPipelineToNoPCList(Pipeline pipeline) {
        // should have been caught upstack, but just in case since public method
        if (!AlaudaUtils.isPipelineStrategyPipeline(pipeline))
            return;
        pipelinesWithNoPCList.add(pipeline);
    }

    private static synchronized void removePipelineFromNoPCList(Pipeline pipeline) {
        pipelinesWithNoPCList.remove(pipeline);
    }

    private static synchronized void clearNoPCList() {
        pipelinesWithNoPCList.clear();
    }

    // trigger any builds whose watch events arrived before the
    // corresponding build config watch events
    public static void flushPipelinesWithNoPCList() {
        HashSet<Pipeline> clone = (HashSet<Pipeline>) pipelinesWithNoPCList.clone();
        clearNoPCList();
        for (Pipeline pipeline : clone) {
            WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
            logger.info("Pipeline flush: "+pipeline.getMetadata().getName()+" - job: "+job);
            if (job != null) {
                try {
                    logger.info("triggering job run for previously skipped pipeline "
                            + pipeline.getMetadata().getName());
                    JenkinsUtils.triggerJob(job, pipeline);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "flushCachedPipelines", e);
                }
            } else {
                addPipelineToNoPCList(pipeline);
            }
        }

    }

    // innerDeleteEventToJenkinsJobRun is the actual delete logic at the heart
    // of deleteEventToJenkinsJobRun
    // that is either in a sync block or not based on the presence of a BC uid
    private static synchronized void innerDeleteEventToJenkinsJobRun(
            final Pipeline pipeline) throws Exception {
        final WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
        if (job != null) {
            ACL.impersonate(ACL.SYSTEM,
                    new NotReallyRoleSensitiveCallable<Void, Exception>() {
                        @Override
                        public Void call() throws Exception {
                            JenkinsUtils.cancelPipeline(job, pipeline, true);

                            JenkinsUtils.deleteRun(job, pipeline);

                            return null;
                        }
                    });
        } else {
            // in case pipeline was created and deleted quickly, prior to seeing BC
            // event, clear out from pre-BC cache
            removePipelineFromNoPCList(pipeline);
        }
    }

    // in response to receiving an Alauda DevOps delete pipeline event, this method
    // will drive
    // the clean up of the Jenkins job run the pipeline is mapped one to one with;
    // as part of that
    // clean up it will synchronize with the pipeline config event watcher to
    // handle pipeline config
    // delete events and pipeline delete events that arrive concurrently and in a
    // nondeterministic
    // order
    private static synchronized void deleteEventToJenkinsJobRun(
            final Pipeline pipeline) throws Exception {
      logger.info("Pipeline delete: "+pipeline.getMetadata().getName());
        List<OwnerReference> ownerRefs = pipeline.getMetadata()
                .getOwnerReferences();
        String pcUid = null;
        for (OwnerReference ref : ownerRefs) {
            if ("PipelineConfig".equals(ref.getKind()) && ref.getUid() != null
                    && ref.getUid().length() > 0) {
                // employ intern to facilitate sync'ing on the same actual
                // object
                pcUid = ref.getUid().intern();
                synchronized (pcUid) {
                    // if entire job already deleted via bc delete, just return
                    if (PipelineConfigToJobMap.getJobFromPipelineConfigUid(pcUid) == null)
                        return;
                    innerDeleteEventToJenkinsJobRun(pipeline);
                    return;
                }
            }
        }
        // otherwise, if something odd is up and there is no parent BC, just
        // clean up
        innerDeleteEventToJenkinsJobRun(pipeline);
    }

  /**
   * Reconciles Jenkins job runs and Alauda DevOps pipelines
   *
   * Deletes all job runs that do not have an associated build in Alauda DevOps
   */
  private static synchronized void reconcileRunsAndPipelines() {
    logger.info("Reconciling job runs and pipelines");

    List<WorkflowJob> jobs = Jenkins.getInstance().getAllItems(WorkflowJob.class);

    for (WorkflowJob job : jobs) {
      WorkflowJobProperty pcpp = job.getProperty(WorkflowJobProperty.class);
      if (pcpp == null) {
        // If we encounter a job without a BuildConfig, skip the reconciliation logic
        continue;
      }
      PipelineList pipelineList = AlaudaUtils.getAuthenticatedAlaudaClient().pipelines()
        .inNamespace(pcpp.getNamespace()).withLabel(Constants.ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG, pcpp.getName()).list();

      logger.info("Checking runs for PipelineConfig " + pcpp.getNamespace() + "/" + pcpp.getName());

      for (WorkflowRun run : job.getBuilds()) {
        boolean found = false;
        JenkinsPipelineCause cause = run.getCause(JenkinsPipelineCause.class);
        for (Pipeline build : pipelineList.getItems()) {
          if (cause != null && cause.getUid().equals(build.getMetadata().getUid())) {
            found = true;
            break;
          }
        }
        if (!found) {
          JenkinsUtils.deleteRun(run);
        }
      }
    }
  }

    @Override
    public final String getName() {
        return "PipelineWatcher";
    }
}
