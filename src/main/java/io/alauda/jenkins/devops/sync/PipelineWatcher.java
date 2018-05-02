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

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Watcher;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;

import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;


import static io.alauda.jenkins.devops.sync.JenkinsUtils.cancelPipeline;
import static java.util.logging.Level.WARNING;

public class PipelineWatcher extends BaseWatcher {
    private static final Logger logger = Logger.getLogger(PipelineWatcher.class
            .getName());

    // the fabric8 classes like Build have equal/hashcode annotations that
    // should allow
    // us to index via the objects themselves;
    // now that listing interval is 5 minutes (used to be 10 seconds), we have
    // seen
    // timing windows where if the build watch events come before build config
    // watch events
    // when both are created in a simultaneous fashion, there is an up to 5
    // minute delay
    // before the job run gets kicked off
    private static final HashSet<Pipeline> pipelinesWithNoPCList = new HashSet<Pipeline>();

    @SuppressFBWarnings("EI_EXPOSE_REP2")
    public PipelineWatcher(String[] namespaces) {
        super(namespaces);
    }

    @Override
    public Runnable getStartTimerTask() {
        return new SafeTimerTask() {
            @Override
            public void doRun() {
                if (!CredentialsUtils.hasCredentials()) {
                    logger.fine("No Alauda Kubernetes Token credential defined.");
                    return;
                }
                // prior to finding new builds poke the PipelineWatcher builds with
                // no BC list and see if we
                // can create job runs for premature builds we already know
                // about
                PipelineWatcher.flushPipelinesWithNoPCList();
                for (String namespace : namespaces) {
                    PipelineList newPipelines = null;
                    try {
                        logger.fine("listing Pipeline resources");
                        // TODO: Filter directly in the API
                        newPipelines = filterNew(AlaudaUtils.getAuthenticatedAlaudaClient()
                                .pipelines()
                                .inNamespace(namespace)
                                .list());
                        onInitialPipelines(newPipelines);
                        logger.fine("handled Pipeline resources");
                    } catch (Exception e) {
                        logger.log(Level.SEVERE,
                                "Failed to load initial Builds: " + e, e);
                    }
                    try {
                        String resourceVersion = "0";
                        if (newPipelines == null) {
                            logger.warning("Unable to get build list; impacts resource version used for watch");
                        } else {
                            resourceVersion = newPipelines.getMetadata()
                                    .getResourceVersion();
                        }
                        synchronized(PipelineWatcher.this) {
                            if (watches.get(namespace) == null) {
                                logger.info("creating Pipeline watch for namespace "
                                        + namespace
                                        + " and resource version "
                                        + resourceVersion);
                                watches.put(
                                        namespace,
                                        AlaudaUtils.getAuthenticatedAlaudaClient()
                                                .pipelines()
                                                .inNamespace(namespace)
                                                .withResourceVersion(
                                                        resourceVersion)
                                                .watch(new WatcherCallback<Pipeline>(
                                                        PipelineWatcher.this,
                                                        namespace)));
                            }
                        }
                    } catch (Exception e) {
                        logger.log(Level.SEVERE,
                                "Failed to load initial Builds: " + e, e);
                    }
                }
                reconcileRunsAndPipelines();
            }
        };
    }

    private PipelineList filterNew(PipelineList list) {
      return JenkinsUtils.filterNew(list);
    }

    public void start() {
        PipelineToActionMapper.initialize();
      logger.info("Now handling startup pipelines");
        super.start();
    }

    @SuppressFBWarnings("SF_SWITCH_NO_DEFAULT")
    public synchronized void eventReceived(Watcher.Action action, Pipeline pipeline) {
        if (!AlaudaUtils.isPipelineStrategyPipeline(pipeline))
            return;
        logger.info("Pipeline event: "+action+" - pipeline "+pipeline.getMetadata().getName());
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
                logger.warning("watch for pipeline " + pipeline.getMetadata().getName() + " received error event ");
                break;
            default:
                logger.warning("watch for pipeline " + pipeline.getMetadata().getName() + " received unknown event " + action);
                break;
            }
        } catch (Exception e) {
            logger.log(WARNING, "Caught: " + e, e);
        }
    }
    @Override
    public <T> void eventReceived(Watcher.Action action, T resource) {
        Pipeline pipeline = (Pipeline)resource;
        eventReceived(action, pipeline);
    }

    public synchronized static void onInitialPipelines(PipelineList pipelineList) {
        if (pipelineList == null)
            return;
        List<Pipeline> items = pipelineList.getItems();
        if (items != null) {

            Collections.sort(items, new Comparator<Pipeline>() {
                @Override
                public int compare(Pipeline p1, Pipeline p2) {
                    if (p1.getMetadata().getAnnotations() == null
                            || p1.getMetadata().getAnnotations()
                                    .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER) == null) {
                        logger.warning("cannot compare pipeline "
                                + p1.getMetadata().getName()
                                + " from namespace "
                                + p1.getMetadata().getNamespace()
                                + ", has bad annotations: "
                                + p1.getMetadata().getAnnotations());
                        return 0;
                    }
                    if (p2.getMetadata().getAnnotations() == null
                            || p2.getMetadata().getAnnotations()
                                    .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER) == null) {
                        logger.warning("cannot compare pipeline "
                                + p2.getMetadata().getName()
                                + " from namespace "
                                + p2.getMetadata().getNamespace()
                                + ", has bad annotations: "
                                + p2.getMetadata().getAnnotations());
                        return 0;
                    }
                    int rc = 0;
                    try {
                        rc = Long.compare(

                                Long.parseLong(p1
                                        .getMetadata()
                                        .getAnnotations()
                                        .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER)),
                                Long.parseLong(p2
                                        .getMetadata()
                                        .getAnnotations()
                                        .get(Constants.ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER)));
                    } catch (Throwable t) {
                        logger.log(Level.FINE, "onInitialPipelines", t);
                    }
                    return rc;
                }
            });

            // We need to sort the builds into their build configs so we can
            // handle build run policies correctly.
            Map<String, PipelineConfig> pipelineConfigMap = new HashMap<>();
            Map<PipelineConfig, List<Pipeline>> pipelineConfigBuildMap = new HashMap<>(
                    items.size());
            for (Pipeline pipe : items) {
                if (!AlaudaUtils.isPipelineStrategyPipeline(pipe))
                    continue;
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
                PipelineConfigProjectProperty bcp = job
                        .getProperty(PipelineConfigProjectProperty.class);
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
                JenkinsUtils.handlePipelineList(job, pipelines, bcp);
            }
        }
    }

    private static synchronized void modifyEventToJenkinsJobRun(Pipeline pipeline) {
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

    public static synchronized void addPipelineToNoPCList(Pipeline pipeline) {
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
    public static synchronized void flushPipelinesWithNoPCList() {
        HashSet<Pipeline> clone = (HashSet<Pipeline>) pipelinesWithNoPCList.clone();
        clearNoPCList();
        for (Pipeline pipeline : clone) {
            WorkflowJob job = JenkinsUtils.getJobFromPipeline(pipeline);
          logger.info("Pipeline flush: "+pipeline.getMetadata().getName()+" - job: "+job);
            if (job != null)
                try {
                    logger.info("triggering job run for previously skipped pipeline "
                            + pipeline.getMetadata().getName());
                    JenkinsUtils.triggerJob(job, pipeline);
                } catch (IOException e) {
                    logger.log(Level.WARNING, "flushCachedPipelines", e);
                }
            else
                addPipelineToNoPCList(pipeline);
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

    List<WorkflowJob> jobs = Jenkins.getActiveInstance().getAllItems(WorkflowJob.class);

    for (WorkflowJob job : jobs) {
      PipelineConfigProjectProperty pcpp = job.getProperty(PipelineConfigProjectProperty.class);
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

}
