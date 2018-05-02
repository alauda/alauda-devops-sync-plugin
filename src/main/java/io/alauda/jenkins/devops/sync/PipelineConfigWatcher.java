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

import com.cloudbees.hudson.plugins.folder.Folder;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.BulkChange;
import hudson.model.ItemGroup;
import hudson.model.Job;
import hudson.model.ParameterDefinition;
import hudson.security.ACL;
import hudson.triggers.SafeTimerTask;
import hudson.triggers.Trigger;
import hudson.util.XStream2;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.api.model.PipelineConfigList;

import io.alauda.kubernetes.client.Watcher;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.AlaudaUtils.*;
import static java.util.logging.Level.SEVERE;

/**
 * Watches {@link PipelineConfig} objects in Alauda DevOps and for WorkflowJobs we
 * ensure there is a suitable Jenkins Job object defined with the correct
 * configuration
 */
public class PipelineConfigWatcher extends BaseWatcher {
  private final Logger logger = Logger.getLogger(getClass().getName());

  // for coordinating between ItemListener.onUpdate and onDeleted both
  // getting called when we delete a job; ID should be combo of namespace
  // and name for BC to properly differentiate; we don't use UUID since
  // when we filter on the ItemListener side the UUID may not be
  // available
  private static final HashSet<String> deletesInProgress = new HashSet<String>();

  public static synchronized void deleteInProgress(String pcName) {
    deletesInProgress.add(pcName);
  }

  public static synchronized boolean isDeleteInProgress(String pcID) {
    return deletesInProgress.contains(pcID);
  }

  public static synchronized void deleteCompleted(String pcID) {
    deletesInProgress.remove(pcID);
  }

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public PipelineConfigWatcher(String[] namespaces) {
    super(namespaces);
    logger.info("PipelineWatcher got these namespaces: "+namespaces);
  }

  public Runnable getStartTimerTask() {
    return new SafeTimerTask() {
      @Override
      public void doRun() {
        if (!CredentialsUtils.hasCredentials()) {
          logger.info("No Alauda Kubernetes Token credential defined.");
          return;
        }
        if (namespaces == null || namespaces.length == 0) {
          logger.info("No namespaces for pipeline config watcher...");
        }

        for (String namespace : namespaces) {
          logger.info("Looking for pipeline configs in namespace "+namespace);
          PipelineConfigList pipelineConfigs = null;
          try {
            logger.info("listing PipelineConfigs resources");
            pipelineConfigs = AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().inNamespace(namespace).list();
            onInitialPipelineConfigs(pipelineConfigs);
            logger.info("handled PipelineConfigs resources");
          } catch (Exception e) {
            logger.log(SEVERE, "Failed to load PipelineConfigs: " + e, e);
          }

          try {
            String resourceVersion = "0";
            if (pipelineConfigs == null) {
              logger.warning("Unable to get pipeline config list; impacts resource version used for watch");
            } else {
              resourceVersion = pipelineConfigs.getMetadata().getResourceVersion();
            }
            synchronized(PipelineConfigWatcher.this) {
              if (watches.get(namespace) == null) {
                logger.info("creating PipelineConfig watch for namespace " + namespace + " and resource version " + resourceVersion);
                watches.put(namespace, AlaudaUtils.getAuthenticatedAlaudaClient().pipelineConfigs().
                  inNamespace(namespace).withResourceVersion(resourceVersion).
                  watch(new WatcherCallback<PipelineConfig>(PipelineConfigWatcher.this, namespace)));
              }
            }
          } catch (Exception e) {
            logger.log(SEVERE, "Failed to load PipelineConfigs: " + e, e);
          }
        }
        // poke the PipelineWatcher builds with no BC list and see if we
        // can create job
        // runs for premature builds

        // TODO: Change to PipelineWatcher
//        PipelineWatcher.flushPipelinesWithNoPCList();

      }
    };
  }

  public synchronized void start() {
    PipelineConfigToJobMap.initializePipelineConfigToJobMap();
    logger.info("Now handling startup pipeline configs!!");
    super.start();

  }

  private synchronized void onInitialPipelineConfigs(PipelineConfigList pipelineConfigs) {
    if (pipelineConfigs == null) {
      return;
    }

    List<PipelineConfig> items = pipelineConfigs.getItems();
    if (items != null) {
      for (PipelineConfig pipelineConfig : items) {
        try {
          if(!isBoundPipelineConfig(pipelineConfig)) {
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
    if(!isBoundPipelineConfig(pipelineConfig)) {
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
          logger.warning("watch for PipelineConfig " + pipelineConfig.getMetadata().getName() + " received error event ");
          break;
        default:
          logger.warning("watch for PipelineConfig " + pipelineConfig.getMetadata().getName() + " received unknown event " + action);
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
                if (!CredentialsUtils.hasCredentials()) {
                  logger.fine("No Alauda Kubernetes Token credential defined.");
                  return;
                }
                // TODO: Change to PipelineList and filter
                PipelineList pipelineList = JenkinsUtils.filterNew(AlaudaUtils.getAuthenticatedAlaudaClient().pipelines().inNamespace(pipelineConfig.getMetadata().getNamespace())
                  .withLabel(Constants.ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG, pipelineConfig.getMetadata().getName()).list());
                if (pipelineList.getItems().size() > 0) {
                  logger.info("pipeline backup query for " + pipelineConfig.getMetadata().getName() + " found new pipelines");
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

  /**
   * Check whether the PipelineConfig is bound with current Jenkins.
   * @param pipelineConfig PipelineConfig
   * @return check result
   */
  private boolean isBoundPipelineConfig(PipelineConfig pipelineConfig) {
    String bindingName = pipelineConfig.getSpec().getJenkinsBinding().getName();
    String namespace = pipelineConfig.getMetadata().getNamespace();

    JenkinsBinding jenkinsBinding = AlaudaUtils.getAuthenticatedAlaudaClient().jenkinsBindings()
      .inNamespace(namespace).withName(bindingName).get();
    if(jenkinsBinding != null) {
      String jenkinsName = jenkinsBinding.getSpec().getJenkins().getName();
      String pluginJenkinsService = GlobalPluginConfiguration.get().getJenkinsService();

      if(!jenkinsName.equals(pluginJenkinsService)) {
        logger.info("PipelineConfig is in Jenkins " + jenkinsName + ", but current Jenkins is " + pluginJenkinsService);
        return false;
      }
    } else {
      logger.warning("Can't found the JenkinsBinding by namespace : " + namespace + "; name : " + bindingName);
      return false;
    }

    return true;
  }

  @Override
  public <T> void eventReceived(Watcher.Action action, T resource) {
    PipelineConfig pc = (PipelineConfig)resource;
    eventReceived(action, pc);
  }

  private void updateJob(WorkflowJob job, InputStream jobStream, String jobName, PipelineConfig pipelineConfig, String existingPipelineRunPolicy, PipelineConfigProjectProperty pipelineConfigProjectProperty) throws IOException {
    Source source = new StreamSource(jobStream);
    job.updateByXml(source);
    job.save();
    logger.info("Updated job " + jobName + " from PipelineConfig " + NamespaceName.create(pipelineConfig) + " with revision: " + pipelineConfig.getMetadata().getResourceVersion());
    if (existingPipelineRunPolicy != null && !existingPipelineRunPolicy.equals(pipelineConfigProjectProperty.getPipelineRunPolicy())) {
      // TODO: Change to schedule pipeline
       JenkinsUtils.maybeScheduleNext(job);
    }
  }

  private void upsertJob(final PipelineConfig pipelineConfig) throws Exception {
    if (AlaudaUtils.isPipelineStrategyPipelineConfig(pipelineConfig)) {
      // sync on intern of name should guarantee sync on same actual obj
      synchronized (pipelineConfig.getMetadata().getUid().intern()) {
        ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
          @Override
          public Void call() throws Exception {
            String jobName = AlaudaUtils.jenkinsJobName(pipelineConfig);
            String jobFullName = AlaudaUtils.jenkinsJobFullName(pipelineConfig);
            WorkflowJob job = PipelineConfigToJobMap.getJobFromPipelineConfig(pipelineConfig);
            Jenkins activeInstance = Jenkins.getActiveInstance();
            ItemGroup parent = activeInstance;
            if (job == null) {
              job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
            }
            boolean newJob = job == null;
            if (newJob) {
              // TODO: this is not used now
//              String disableOn = getAnnotation(pipelineConfig, DISABLE_SYNC_CREATE);
//              if (disableOn != null && disableOn.length() > 0) {
//                logger.fine("Not creating missing jenkins job " + jobFullName + " due to annotation: " + DISABLE_SYNC_CREATE);
//                return null;
//              }
              parent = AlaudaUtils.getFullNameParent(activeInstance, jobFullName, AlaudaUtils.getNamespace(pipelineConfig));
              job = new WorkflowJob(parent, jobName);
            }
            BulkChange bk = new BulkChange(job);

            job.setDisplayName(AlaudaUtils.jenkinsJobDisplayName(pipelineConfig));

            FlowDefinition flowFromPipelineConfig = PipelineConfigToJobMapper.mapPipelineConfigToFlow(pipelineConfig);
            if (flowFromPipelineConfig == null) {
              return null;
            }

            job.setDefinition(flowFromPipelineConfig);

            String existingBuildRunPolicy = null;

            PipelineConfigProjectProperty pipelineConfigProjectProperty = job.getProperty(PipelineConfigProjectProperty.class);
            if (pipelineConfigProjectProperty != null) {
              existingBuildRunPolicy = pipelineConfigProjectProperty.getPipelineRunPolicy();
              long updatedBCResourceVersion = AlaudaUtils.parseResourceVersion(pipelineConfig);
              long oldBCResourceVersion = parseResourceVersion(pipelineConfigProjectProperty.getResourceVersion());
              PipelineConfigProjectProperty newProperty = new PipelineConfigProjectProperty(pipelineConfig);
              if (updatedBCResourceVersion <= oldBCResourceVersion && newProperty.getUid().equals(pipelineConfigProjectProperty.getUid()) && newProperty.getNamespace().equals(pipelineConfigProjectProperty.getNamespace())
                && newProperty.getName().equals(pipelineConfigProjectProperty.getName()) && newProperty.getPipelineRunPolicy().equals(pipelineConfigProjectProperty.getPipelineRunPolicy())) {
                return null;
              }
              pipelineConfigProjectProperty.setUid(newProperty.getUid());
              pipelineConfigProjectProperty.setNamespace(newProperty.getNamespace());
              pipelineConfigProjectProperty.setName(newProperty.getName());
              pipelineConfigProjectProperty.setResourceVersion(newProperty.getResourceVersion());
              pipelineConfigProjectProperty.setPipelineRunPolicy(newProperty.getPipelineRunPolicy());
            } else {
              job.addProperty(new PipelineConfigProjectProperty(pipelineConfig));
            }

            // (re)populate job param list with any parameters
            // from the PipelineConfig
            Map<String, ParameterDefinition> paramMap = JenkinsUtils.addJobParamForPipelineParameters(job, pipelineConfig.getSpec().getParameters(), true);

            job.setConcurrentBuild(!(pipelineConfig.getSpec().getRunPolicy().equals(PipelineRunPolicy.SERIAL)));

            // Setting triggers according to pipeline config
            List<Trigger<?>> triggers = JenkinsUtils.addJobTriggers(job, pipelineConfig.getSpec().getTriggers());

            InputStream jobStream = new StringInputStream(new XStream2().toXML(job));

            if (newJob) {
              try {
                if (parent instanceof Folder) {
                  Folder folder = (Folder) parent;
                  folder.createProjectFromXML(jobName, jobStream).save();
                } else {
                  activeInstance.createProjectFromXML(jobName, jobStream).save();
                }

                logger.info("Created job " + jobName + " from PipelineConfig " + NamespaceName.create(pipelineConfig) + " with revision: " + pipelineConfig.getMetadata().getResourceVersion());
              } catch (IllegalArgumentException e) {
                // see
                // https://github.com/openshift/jenkins-sync-plugin/issues/117,
                // jenkins might reload existing jobs on
                // startup between the
                // newJob check above and when we make
                // the createProjectFromXML call; if so,
                // retry as an update
                updateJob(job, jobStream, jobName, pipelineConfig, existingBuildRunPolicy, pipelineConfigProjectProperty);
              }
            } else {
              updateJob(job, jobStream, jobName, pipelineConfig, existingBuildRunPolicy, pipelineConfigProjectProperty);
            }
            bk.commit();
            String fullName = job.getFullName();
            WorkflowJob workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);
            if (workflowJob == null && parent instanceof Folder) {
              // we should never need this but just in
              // case there's an
              // odd timing issue or something...
              Folder folder = (Folder) parent;
              folder.add(job, jobName);
              workflowJob = activeInstance.getItemByFullName(fullName, WorkflowJob.class);

            }
            if (workflowJob == null) {
              logger.warning("Could not find created job " + fullName + " for PipelineConfig: " + AlaudaUtils.getNamespace(pipelineConfig) + "/" + AlaudaUtils.getName(pipelineConfig));
            } else {
              JenkinsUtils.verifyEnvVars(paramMap, workflowJob);
              PipelineConfigToJobMap.putJobWithPipelineConfig(workflowJob, pipelineConfig);
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
      return;
    }

    // no longer a Jenkins build so lets delete it if it exists
    deleteEventToJenkinsJob(pipelineConfig);
  }

  // innerDeleteEventToJenkinsJob is the actual delete logic at the heart of
  // deleteEventToJenkinsJob
  // that is either in a sync block or not based on the presence of a BC uid
  private void innerDeleteEventToJenkinsJob(final PipelineConfig pipelineConfig) throws Exception {
    final Job job = PipelineConfigToJobMap.getJobFromPipelineConfig(pipelineConfig);
    if (job != null) {
      // employ intern of the BC UID to facilitate sync'ing on the same
      // actual object
      synchronized (pipelineConfig.getMetadata().getUid().intern()) {
        ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, Exception>() {
          @Override
          public Void call() throws Exception {
            try {
              deleteInProgress(pipelineConfig.getMetadata().getNamespace() + pipelineConfig.getMetadata().getName());
              job.delete();
            } finally {
              PipelineConfigToJobMap.removeJobWithPipelineConfig(pipelineConfig);
              Jenkins.getActiveInstance().rebuildDependencyGraphAsync();
              deleteCompleted(pipelineConfig.getMetadata().getNamespace() + pipelineConfig.getMetadata().getName());
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

  // in response to receiving an openshift delete build config event, this
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
}
