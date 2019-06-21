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
package io.alauda.jenkins.devops.sync.util;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.Queue;
import hudson.model.*;
import hudson.model.queue.QueueTaskFuture;
import hudson.plugins.git.RevisionParameterAction;
import hudson.security.ACL;
import hudson.triggers.SCMTrigger;
import hudson.triggers.SafeTimerTask;
import hudson.triggers.TimerTrigger;
import hudson.triggers.Trigger;
import io.alauda.devops.java.client.models.*;
import io.alauda.jenkins.devops.sync.*;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.controller.PipelineConfigController;
import io.alauda.jenkins.devops.sync.controller.PipelineController;
import io.kubernetes.client.models.V1ObjectMeta;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.MultiBranchProject;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;
import org.acegisecurity.context.SecurityContext;
import org.acegisecurity.context.SecurityContextHolder;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTPipelineDef;
import org.jenkinsci.plugins.pipeline.modeldefinition.parser.Converter;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.*;
import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.isCancelled;
import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.updatePipelinePhase;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;

/**
 * @author suren
 */
public abstract class JenkinsUtils {
	private static final Logger LOGGER = Logger.getLogger(JenkinsUtils.class.getName());
	public static final String PARAM_FROM_ENV_DESCRIPTION = "From Alauda DevOps PipelineConfig Parameter";

	private JenkinsUtils(){}

	public static Job getJob(String job) {
		TopLevelItem item = Jenkins.getInstance().getItem(job);
		if (item instanceof Job) {
			return (Job) item;
		}
		return null;
	}

	@NotNull
	public static String getRootUrl() {
		// TODO is there a better place to find this?
		String root = Jenkins.getInstance().getRootUrl();
		return StringUtils.isBlank(root) ? ROOT_URL : root;
	}

	public static boolean verifyEnvVars(Map<String, ParameterDefinition> paramMap, WorkflowJob workflowJob) {
        if (paramMap != null) {
            String fullName = workflowJob.getFullName();
            WorkflowJob job = Jenkins.getInstance().getItemByFullName(fullName, WorkflowJob.class);
            if (job == null) {
                // this should not occur if an impersonate call has been made higher up
                // the stack
                LOGGER.warning(() -> "A run of workflow job " + workflowJob.getName() + " unexpectantly not saved to disk.");
                return false;
            }
            ParametersDefinitionProperty props = job.getProperty(ParametersDefinitionProperty.class);
            List<String> names = props.getParameterDefinitionNames();
            for (String name : names) {
                if (!paramMap.containsKey(name)) {
                    LOGGER.warning(() -> "A run of workflow job " + job.getName() + " was expecting parameter "
                            + name + ", but it is not in the parameter list");
                    return false;
                }
            }
        }
	    return true;
	}

//	public static Map<String, ParameterDefinition> addJobParamForBuildEnvs(@Nonnull WorkflowJob job,
//            @Nonnull JenkinsPipelineBuildStrategy strat,
//			boolean replaceExisting) throws IOException {
//		List<EnvVar> envs = strat.getEnv();
//        Map<String, ParameterDefinition> paramMap = null;
//		if (envs != null && envs.size() > 0) {
//			// build list of current env var names for possible deletion of env
//			// vars currently stored
//			// as job params
//			List<String> envKeys = new ArrayList<>();
//			for (EnvVar env : envs) {
//				envKeys.add(env.getName());
//			}
//
//			// get existing property defs, including any manually added from the
//			// jenkins console independent of BC
//			ParametersDefinitionProperty params = job.removeProperty(ParametersDefinitionProperty.class);
//			paramMap = new HashMap<>();
//			// store any existing parameters in map for easy key lookup
//			if (params != null) {
//				List<ParameterDefinition> existingParamList = params.getParameterDefinitions();
//				for (ParameterDefinition param : existingParamList) {
//                    paramMap.put(param.getName(), param);
//				}
//			}
//
//			for (EnvVar env : envs) {
//				if (replaceExisting || !paramMap.containsKey(env.getName())) {
//					StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), env.getValue(),
//							PARAM_FROM_ENV_DESCRIPTION);
//					paramMap.put(env.getName(), envVar);
//				}
//			}
//
//			List<ParameterDefinition> newParamList = new ArrayList<>(paramMap.values());
//			job.addProperty(new ParametersDefinitionProperty(newParamList));
//		}
//		// force save here ... seen some timing issues with concurrent job updates and run initiations
//		job.save();
//		return paramMap;
//	}

    public static Map<String, ParameterDefinition> addJobParamForPipelineParameters(WorkflowJob job,
                                                                                    List<V1alpha1PipelineParameter> params, boolean replaceExisting) throws IOException {
        // get existing property defs, including any manually added from the
        // jenkins console independent of PC
        ParametersDefinitionProperty jenkinsParams = job.removeProperty(ParametersDefinitionProperty.class);

        Map<String, ParameterDefinition> paramMap = null;
        if (params != null && params.size() > 0) {
            // build list of current env var names for possible deletion of env
            // vars currently stored
            // as job params
            // builds a list of job parameters

            List<String> envKeys = new ArrayList<>();
            for (V1alpha1PipelineParameter parameter : params) {
                envKeys.add(parameter.getName());
            }
            paramMap = new HashMap<>();
            // store any existing parameters in map for easy key lookup
            if (jenkinsParams != null) {
                List<ParameterDefinition> existingParamList = jenkinsParams.getParameterDefinitions();
                for (ParameterDefinition param : existingParamList) {
                    // if a user supplied param, add
                    if (param.getDescription() == null || !param.getDescription().equals(PARAM_FROM_ENV_DESCRIPTION))
                        paramMap.put(param.getName(), param);
                    else if (envKeys.contains(param.getName())) {
                        // the env var still exists on the PipelineConfig side so
                        // keep
                        paramMap.put(param.getName(), param);
                    }
                }
            }

            for (V1alpha1PipelineParameter param : params) {
                ParameterDefinition jenkinsParam = null;
                switch (param.getType()) {
                    case PIPELINE_PARAMETER_TYPE_STRING:
                        jenkinsParam = new StringParameterDefinition(param.getName(), param.getValue(), param.getDescription());
                        break;
                    case PIPELINE_PARAMETER_TYPE_BOOLEAN:
                        jenkinsParam = new BooleanParameterDefinition(param.getName(), Boolean.valueOf(param.getValue()), param.getDescription());
                        break;
                    default:
                        LOGGER.warning(() -> "Parameter type `" + param.getType() + "` is not supported.. skipping...");
                        break;
                }

                if (jenkinsParam == null) {
                    continue;
                }
                // TODO: This is made differently from the original source
                // Need revisit this part if the parameters
                if (replaceExisting || !paramMap.containsKey(jenkinsParam.getName())) {
                    paramMap.put(jenkinsParam.getName(), jenkinsParam);
                }
            }

            List<ParameterDefinition> newParamList = new ArrayList<>(paramMap.values());
            job.addProperty(new ParametersDefinitionProperty(newParamList));
        }

        // force save here ... seen some timing issues with concurrent job updates and run initiations
        job.save();
        return paramMap;
    }

    /**
     * Override job's triggers
     * @param job
     * @param triggers
     * @return
     * @throws IOException
     */
    @NotNull
    public static List<ANTLRException> setJobTriggers(@Nonnull WorkflowJob job, List<V1alpha1PipelineTrigger> triggers) throws IOException {
        List<ANTLRException> exceptions = new ArrayList<>();
        if (CollectionUtils.isEmpty(triggers)) {
            return exceptions;
        }

        job.removeProperty(PipelineTriggersJobProperty.class);
        LOGGER.info(() -> "PipelineTrigger's count is " + triggers.size());

        for (V1alpha1PipelineTrigger pipelineTrigger : triggers) {
            Trigger trigger = null;
            final String type = pipelineTrigger.getType();
            if(type == null) {
                continue;
            }

            switch (type) {
                case PIPELINE_TRIGGER_TYPE_CODE_CHANGE:
                    V1alpha1PipelineTriggerCodeChange codeTrigger = pipelineTrigger.getCodeChange();

                    if (codeTrigger == null || !codeTrigger.isEnabled()) {
                        LOGGER.warning(() -> "Trigger type `" + PIPELINE_TRIGGER_TYPE_CODE_CHANGE + "` has empty description or is disabled...");
                        break;
                    }

                    try {
                        trigger = new SCMTrigger(codeTrigger.getPeriodicCheck());

                        LOGGER.info(() -> "Add CodeChangeTrigger.");
                    } catch (ANTLRException exc) {
                        LOGGER.log(Level.SEVERE, String.format("Error processing trigger type %s", PIPELINE_TRIGGER_TYPE_CODE_CHANGE), exc);
                        exceptions.add(exc);
                    }

                    break;
                case PIPELINE_TRIGGER_TYPE_CRON:
                    V1alpha1PipelineTriggerCron cronTrigger = pipelineTrigger.getCron();
                    if (cronTrigger == null || !cronTrigger.isEnabled()) {
                        LOGGER.warning(() -> "Trigger type `" + PIPELINE_TRIGGER_TYPE_CRON + "` has empty description or is disabled...");
                        break;
                    }

                    try {
                        trigger = new TimerTrigger(cronTrigger.getRule());

                        LOGGER.info(() -> "Add CronTrigger.");
                    } catch (ANTLRException exc) {
                        LOGGER.log(Level.SEVERE, String.format("Error processing trigger type %s", PIPELINE_TRIGGER_TYPE_CRON), exc);
                        exceptions.add(exc);
                    }

                    break;
                default:
                    LOGGER.warning(() -> "Trigger type `" + pipelineTrigger.getType() + "` is not supported... skipping...");
            }

            if(trigger != null) {
                job.addTrigger(trigger);
            }
        }

        LOGGER.info(() -> "Job trigger save done.");

        return exceptions;
    }

    @CheckForNull
	public static List<Action> putJobRunParamsFromEnvAndUIParams(List<V1alpha1PipelineParameter> pipelineParameters,
                                                                 List<Action> buildActions) {
        if(buildActions == null || pipelineParameters == null) {
            return buildActions;
        }

        List<ParameterValue> envVarList = getParameterValues(pipelineParameters);
        if (envVarList.size() == 0) {
            return buildActions;
        }

        buildActions.add(new ParametersAction(envVarList));

		return buildActions;
	}

	@Nonnull
    public static List<ParameterValue> getParameterValues(List<V1alpha1PipelineParameter> pipelineParameters) {
        List<ParameterValue> envVarList = new ArrayList<>();
        if (pipelineParameters == null) {
            return envVarList;
        }

        for (V1alpha1PipelineParameter pipeParam : pipelineParameters) {
            ParameterValue paramValue = null;
            String type = pipeParam.getType();
            if(type == null) {
                continue;
            }

            switch (type) {
                case PIPELINE_PARAMETER_TYPE_STRING:
                    paramValue = new StringParameterValue(pipeParam.getName(),
                            pipeParam.getValue(), pipeParam.getDescription());
                    break;
                case PIPELINE_PARAMETER_TYPE_BOOLEAN:
                    paramValue = new BooleanParameterValue(pipeParam.getName(),
                            Boolean.valueOf(pipeParam.getValue()), pipeParam.getDescription());
                    break;
                default:
                    LOGGER.warning(() -> "Parameter type `" + pipeParam.getType() + "` is not supported.. skipping...");
                    break;
            }

            if (paramValue != null) {
                envVarList.add(paramValue);
            }
        }

        return envVarList;
    }

    public static boolean triggerJob(@Nonnull WorkflowJob job, @Nonnull V1alpha1Pipeline pipeline)
            throws IOException {
        final V1ObjectMeta pipMeta = pipeline.getMetadata();
        final String namespace = pipMeta.getNamespace();
        final String pipelineName = pipMeta.getName();
	    LOGGER.info(() -> "will trigger pipeline: " + pipelineName);

        if (isAlreadyTriggered(job, pipeline)) {
            LOGGER.info(() -> "pipeline already triggered: "+pipelineName);
            return false;
        }

        AlaudaJobProperty pcProp = job.getProperty(WorkflowJobProperty.class);
        if (pcProp == null) {
            if(job.getParent() instanceof WorkflowMultiBranchProject) {
                pcProp = ((WorkflowMultiBranchProject) job.getParent()).getProperties().get(MultiBranchProperty.class);
            }
        }

        if(pcProp == null) {
            LOGGER.warning(() -> "aborting trigger of pipeline " + pipeline.getMetadata().getNamespace() + "/"+ pipeline.getMetadata().getName()
                    + "because of missing pc project property");
            return false;
        }

        final String pipelineConfigName = pipeline.getSpec().getPipelineConfig().getName();
        V1alpha1PipelineConfig pipelineConfig = PipelineConfigController.getCurrentPipelineConfigController().getPipelineConfig(namespace, pipelineConfigName);
        if (pipelineConfig == null) {
            LOGGER.info(() -> "pipeline config not found....: "+pipelineName+" - config name "+pipelineConfigName);
            return false;
        }

        // sync on intern of name should guarantee sync on same actual obj
        synchronized (pipelineConfig.getMetadata().getUid().intern()) {
          LOGGER.info(() -> "pipeline config source credentials: "+pipelineConfig.getMetadata().getName());

            // We need to ensure that we do not remove
            // existing Causes from a Run since other
            // plugins may rely on them.
            List<Cause> newCauses = new ArrayList<>();
            newCauses.add(new JenkinsPipelineCause(pipeline, pcProp.getUid()));
            CauseAction originalCauseAction = PipelineToActionMapper.removeCauseAction(pipelineName);
            if (originalCauseAction != null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Adding existing causes...");
                    for (Cause c : originalCauseAction.getCauses()) {
                        LOGGER.log(Level.FINE, "trigger error", c);
                    }
                }
                newCauses.addAll(originalCauseAction.getCauses());
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (Cause c : newCauses) {
                        LOGGER.log(Level.FINE, "trigger error", c);
                    }
                }
            }

            List<Action> pipelineActions = new ArrayList<>();
            CauseAction bCauseAction = new CauseAction(newCauses);
            pipelineActions.add(bCauseAction);

            V1alpha1PipelineSourceGit sourceGit = pipeline.getSpec().getSource().getGit();
            String commit = null;
            if (pipMeta.getAnnotations() != null && pipMeta.getAnnotations().containsKey(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT)) {
              commit = pipMeta.getAnnotations().get(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT);
            }

          if (sourceGit != null && commit != null) {
            try {
              URIish repoURL = new URIish(sourceGit.getUri());
              pipelineActions.add(new RevisionParameterAction(commit, repoURL));
            } catch (URISyntaxException e) {
              LOGGER.log(SEVERE, "Failed to parse git repo URL" + sourceGit.getUri(), e);
            }
          }

          LOGGER.info("pipeline got cause....: "+pipelineName+" pipeline actions "+pipelineActions);

            // params added by user in jenkins ui
            PipelineToActionMapper.removeParameterAction(pipelineName);

            putJobRunParamsFromEnvAndUIParams(pipeline.getSpec().getParameters(), pipelineActions);

            //no reason add it again in here
            //PipelineConfigToJobMap.putJobWithPipelineConfig(job, pipelineConfig);
            LOGGER.info(() -> "pipeline config update with job: "+pipelineName+" pipeline config "+pipelineConfig.getMetadata().getName());

            Action[] actionArray;
            if(pipelineActions.size() == 0) {
                actionArray = new Action[]{};
            } else {
                actionArray = pipelineActions.toArray(new Action[pipelineActions.size()]);
            }

            QueueTaskFuture<WorkflowRun> queueTaskFuture = job.scheduleBuild2(0, actionArray);
            if (queueTaskFuture != null) {
                // TODO should offer a better solution
                // TODO should we add an extension point here?
                if(job.getParent() instanceof MultiBranchProject) {
                    BranchProjectFactory factory = ((MultiBranchProject) job.getParent()).getProjectFactory();

                    SCMRevisionAction revisionAction = null;
                    for(Action action : actionArray) {
                        if(action instanceof CauseAction) {
                            List<Cause> causes = ((CauseAction) action).getCauses();
                            if(causes != null) {
                                for(Cause cause : causes) {
                                    if(cause instanceof SCMRevisionAction) {
                                        revisionAction = (SCMRevisionAction) cause;
                                        break;
                                    }
                                }
                            }
                        }
                    }

                    if(revisionAction != null && factory != null) {
                        factory.setRevisionHash(job, revisionAction.getRevision());
                    }
                }

                updatePipelinePhase(pipeline, QUEUED);
                // If builds are queued too quickly, Jenkins can add the cause
                // to the previous queued pipeline so let's add a tiny
                // sleep.
                try {
                    Thread.sleep(50l);
                } catch (InterruptedException e) {
                    LOGGER.log(Level.SEVERE, "updatePipelinePhase Interrupted", e);
                    Thread.currentThread().interrupt();
                }
                return true;
            }

            updatePipelinePhase(pipeline, FAILED);
            LOGGER.info(() -> "Will not schedule build for this pipeline: "+pipelineName);

            return false;
        }
    }

	private static boolean isAlreadyTriggered(WorkflowJob job, V1alpha1Pipeline pipeline) {
		return getRun(job, pipeline) != null;
	}

	public synchronized static void cancelPipeline(WorkflowJob job, V1alpha1Pipeline pipeline) {
		cancelPipeline(job, pipeline, false);
	}

	public synchronized static void cancelPipeline(WorkflowJob job, V1alpha1Pipeline pipeline, boolean deleted) {
		if (!cancelQueuedPipeline(job, pipeline)) {
            cancelRunningPipeline(job, pipeline);
		}

		if (deleted) {
			return;
		}

        updatePipelinePhase(pipeline, CANCELLED);
	}

	private static WorkflowRun getRun(WorkflowJob job, V1alpha1Pipeline pipeline) {
		if (pipeline != null && pipeline.getMetadata() != null) {
			return getRun(job, pipeline.getMetadata().getUid());
		}
		return null;
	}

	private static WorkflowRun getRun(WorkflowJob job, String pipelineUid) {
		for (WorkflowRun run : job.getBuilds()) {
			JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);
			if (cause != null && cause.getUid().equals(pipelineUid)) {
				return run;
			}
		}
		return null;
	}

	public static void deleteRun(WorkflowJob workflowJob, V1alpha1Pipeline pipeline) {
        WorkflowRun run = JenkinsUtils.getRun(workflowJob, pipeline);
        if(run != null) {
            JenkinsUtils.deleteRun(run);
        }
    }

	public synchronized static void deleteRun(WorkflowRun run) {
        try {
          LOGGER.info("Deleting run: " + run.toString());
            run.delete();
        } catch (IOException e) {
            LOGGER.warning(() -> "Unable to delete run " + run.toString() + ":" + e.getMessage());
        }
	}

	private static boolean cancelRunningPipeline(WorkflowJob job, V1alpha1Pipeline pipeline) {
		String pipelineUid = pipeline.getMetadata().getUid();
		WorkflowRun run = getRun(job, pipelineUid);
		if (run != null && run.isBuilding()) {
			terminateRun(run);
			return true;
		}
		return false;
	}

	private static boolean cancelNotYetStartedPipeline(WorkflowJob job, V1alpha1Pipeline pipeline) {
		String pipelineUid = pipeline.getMetadata().getUid();
		WorkflowRun run = getRun(job, pipelineUid);
		if (run != null && run.hasntStartedYet()) {
			terminateRun(run);
			return true;
		}
		return false;
	}

	private static void terminateRun(final WorkflowRun run) {
		ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, RuntimeException>() {
			@Override
			public Void call() throws RuntimeException {
				run.doTerm();
				Timer.get().schedule(new SafeTimerTask() {
					@Override
					public void doRun() {
						ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Void, RuntimeException>() {
							@Override
							public Void call() throws RuntimeException {
								run.doKill();
								return null;
							}
						});
					}
				}, 5, TimeUnit.SECONDS);
				return null;
			}
		});
	}

	@SuppressFBWarnings("SE_BAD_FIELD")
	public static boolean cancelQueuedPipeline(WorkflowJob job, V1alpha1Pipeline pipeline) {
	  LOGGER.info("cancelling queued pipeline: "+pipeline.getMetadata().getName());
		String pipelineUid = pipeline.getMetadata().getUid();
		final Queue pipelineQueue = Jenkins.getInstance().getQueue();
		for (final Queue.Item item : pipelineQueue.getItems()) {
			for (Cause cause : item.getCauses()) {
				if (cause instanceof JenkinsPipelineCause && ((JenkinsPipelineCause) cause).getUid().equals(pipelineUid)) {
					return ACL.impersonate(ACL.SYSTEM, new NotReallyRoleSensitiveCallable<Boolean, RuntimeException>() {
						@Override
						public Boolean call() throws RuntimeException {
							pipelineQueue.cancel(item);
							return true;
						}
					});
				}
			}
		}
		return cancelNotYetStartedPipeline(job, pipeline);
	}

	public static void cancelQueuedBuilds(WorkflowJob job, String pcUid) {
        LOGGER.info(() -> "cancelling queued pipeline by uuid: "+pcUid);
		Queue pipelineQueue = Jenkins.getInstance().getQueue();
		for (Queue.Item item : pipelineQueue.getItems()) {
            JenkinsPipelineCause pipelineCause = PipelineUtils.findAlaudaCause(item);
            if(pipelineCause == null) {
                continue;
            }

            if (pipelineCause.getPipelineConfigUid().equals(pcUid)) {
                V1alpha1Pipeline pipeline = new V1alpha1PipelineBuilder().withMetadata(new V1ObjectMeta().namespace(pipelineCause.getNamespace())
                        .name(pipelineCause.getName())).build();
                cancelQueuedPipeline(job, pipeline);
            }
		}
	}

    /**
     * Find the workflow job. The job could be normal or multi-branch pipeline job.
     * @param pipeline pipeline is the build history for pipelineJob
     * @return workflow job
     */
	public static WorkflowJob getJobFromPipeline(@Nonnull V1alpha1Pipeline pipeline) {
		String configName = pipeline.getSpec().getPipelineConfig().getName();
        V1alpha1PipelineConfig pipelineConfig =
                PipelineConfigController.getCurrentPipelineConfigController()
                        .getPipelineConfig(pipeline.getMetadata().getNamespace(), configName);
		if (pipelineConfig == null) {
		    LOGGER.log(WARNING, String.format("Unable to get pipelineconfig '%s/%s' from pipeline '%s/%s'",
                    pipeline.getMetadata().getNamespace(), configName, pipeline.getMetadata().getNamespace(), pipeline.getMetadata().getName()));
			return null;
		}

        if(PipelineConfigUtils.isMultiBranch(pipelineConfig)) {
            Map<String, String> annotations = pipeline.getMetadata().getAnnotations();
            if(annotations == null) {
                V1ObjectMeta meta = pipeline.getMetadata();
                LOGGER.severe(String.format("Pipeline [%s,%s] don't have annotations, can't find the Workflow.",
                        meta.getNamespace(), meta.getName()));
                return null;
            }

            String branchName = annotations.get(Annotations.MULTI_BRANCH_NAME);
            WorkflowMultiBranchProject project = PipelineConfigToJobMap.getMultiBranchByPC(pipelineConfig);
            if(project != null) {
                final SecurityContext previousContext = ACL.impersonate(ACL.SYSTEM);
                try {
                    WorkflowJob item = project.getItem(branchName);
                    if(item == null) {
                        LOGGER.warning(String.format("Can't find item by branchName %s", branchName));
                    }
                    return item;
                } finally {
                    SecurityContextHolder.setContext(previousContext);
                }
            } else {
                LOGGER.warning(String.format("Can't find multiBranchProject %s", pipelineConfig.getMetadata().getName()));
            }
        } else {
            return PipelineConfigToJobMap.getJobFromPipelineConfig(pipelineConfig);
        }

        return null;
	}

    public static void maybeScheduleNext(WorkflowJob job) {
        WorkflowJobProperty pcp = WorkflowJobUtils.getAlaudaProperty(job);
        if (pcp == null) {
            return;
        }

        List<V1alpha1Pipeline> pipelines = PipelineController.getCurrentPipelineController()
                .listPipelines(pcp.getNamespace())
                .stream()
                .filter(pipe -> {
                    Map<String, String> labels = pipe.getMetadata().getLabels();
                    if (labels == null) {
                        return false;
                    }
                    return pcp.getName().equals(labels.get(ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG)) && pipe.getStatus().getPhase().equals(PENDING);
                }).collect(Collectors.toList());

        handlePipelineList(job, pipelines);
    }

	public static void handlePipelineList(WorkflowJob job, List<V1alpha1Pipeline> pipelines) {
		if (pipelines.isEmpty()) {
			return;
		}
        Collections.sort(pipelines, new PipelineComparator());
		boolean isSerial = !job.isConcurrentBuild();
		boolean jobIsBuilding = job.isBuilding();
		for (int i = 0; i < pipelines.size(); i++) {
            V1alpha1Pipeline p = pipelines.get(i);
			if (!AlaudaUtils.isPipelineStrategyPipeline(p))
				continue;
			// For SerialLatestOnly we should try to cancel all pipelines before
			// the latest one requested.
            if (jobIsBuilding && !isCancelled(p.getStatus())) {
                return;
            }

            if (i < pipelines.size() - 1) {
                cancelQueuedPipeline(job, p);
                // TODO: maybe not necessary?
                updatePipelinePhase(p, CANCELLED);
                continue;
            }

			boolean buildAdded = false;
			try {
				buildAdded = PipelineController.addEventToJenkinsJobRun(p);
			} catch (IOException e) {
				V1ObjectMeta meta = p.getMetadata();
				LOGGER.log(WARNING, "Failed to add new build " + meta.getNamespace() + "/" + meta.getName(), e);
			}
			// If it's a serial build then we only need to schedule the first
			// build request.
			if (isSerial && buildAdded) {
				return;
			}
		}
	}

    @Nonnull
    public static String getFullJobName(@Nonnull WorkflowJob job) {
		return job.getRelativeNameFrom(Jenkins.getInstance());
	}

	@Nonnull
	public static String getBuildConfigName(@Nonnull WorkflowJob job) {
		String name = getFullJobName(job);
		AlaudaSyncGlobalConfiguration config = AlaudaSyncGlobalConfiguration.get();
		String[] paths = name.split("/");
		if (paths.length > 1) {
			String orgName = paths[0];
			if (StringUtils.isNotBlank(orgName)) {
				if (config != null) {
					String skipOrganizationPrefix = config.getSkipOrganizationPrefix();
					if (StringUtils.isEmpty(skipOrganizationPrefix)) {
						config.setSkipOrganizationPrefix(orgName);
						skipOrganizationPrefix = config.getSkipOrganizationPrefix();
					}

					// if the default organization lets strip the organization name from the prefix
					int prefixLength = orgName.length() + 1;
					if (orgName.equals(skipOrganizationPrefix) && name.length() > prefixLength) {
						name = name.substring(prefixLength);
					}
				}
			}
		}

		// lets avoid the .master postfixes as we treat master as the default branch
		// name
		String masterSuffix = "/master";
		if (config != null) {
			String skipBranchSuffix = config.getSkipBranchSuffix();
			if (StringUtils.isEmpty(skipBranchSuffix)) {
				config.setSkipBranchSuffix("master");
				skipBranchSuffix = config.getSkipBranchSuffix();
			}
			masterSuffix = "/" + skipBranchSuffix;
		}
		if (name.endsWith(masterSuffix) && name.length() > masterSuffix.length()) {
			name = name.substring(0, name.length() - masterSuffix.length());
		}
		return name;
	}

	@Nonnull
    public static String formatJenkinsfile(String unformattedJenkinsfile) throws IOException {
        ModelASTPipelineDef pipelineDef = Converter.scriptToPipelineDef(unformattedJenkinsfile);
        if (pipelineDef == null) {
            throw new IOException("Jenkinsfile content '" + unformattedJenkinsfile + "' did not contain the 'pipeline' step or miss some steps");
        }
        return pipelineDef.toPrettyGroovy();
    }

    /**
     * TODO consider gather with other methods
     * @param run
     * @return
     */
	public static boolean fromMultiBranch(@NotNull Run run) {
        Job wfJob = run.getParent();
        if(!(wfJob instanceof WorkflowJob)) {
            return false;
        }

        return (wfJob.getParent() instanceof WorkflowMultiBranchProject);
    }
}
