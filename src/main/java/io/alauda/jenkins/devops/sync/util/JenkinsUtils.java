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
package io.alauda.jenkins.devops.sync.util;

import antlr.ANTLRException;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import hudson.model.*;
import hudson.model.Job;
import hudson.plugins.git.RevisionParameterAction;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.triggers.*;
import io.alauda.jenkins.devops.sync.*;
import io.alauda.kubernetes.api.model.*;
import io.alauda.devops.api.model.JenkinsPipelineBuildStrategy;
import jenkins.model.Jenkins;
import jenkins.security.NotReallyRoleSensitiveCallable;
import jenkins.util.Timer;

import org.apache.commons.lang.StringUtils;
import org.csanchez.jenkins.plugins.kubernetes.KubernetesCloud;
import org.csanchez.jenkins.plugins.kubernetes.PodTemplate;
import org.csanchez.jenkins.plugins.kubernetes.PodVolumes;
import org.eclipse.jgit.transport.URIish;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import com.cloudbees.plugins.credentials.CredentialsParameterDefinition;
import org.jenkinsci.plugins.workflow.job.properties.PipelineTriggersJobProperty;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.QUEUED;
import static io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap.getJobFromPipelineConfig;
import static io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap.putJobWithPipelineConfig;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.CANCELLED;
import static io.alauda.jenkins.devops.sync.watcher.PipelineWatcher.addEventToJenkinsJobRun;
import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static io.alauda.jenkins.devops.sync.util.CredentialsUtils.updateSourceCredentials;
import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.*;
import static java.util.Collections.sort;
import static java.util.logging.Level.SEVERE;
import static java.util.logging.Level.WARNING;
import static org.apache.commons.lang.StringUtils.isBlank;

/**
 * @author suren
 */
public class JenkinsUtils {
	private static final Logger LOGGER = Logger.getLogger(JenkinsUtils.class.getName());
	private static final String PARAM_FROM_ENV_DESCRIPTION = "From Alauda DevOps PipelineConfig Parameter";

	public static Job getJob(String job) {
		TopLevelItem item = Jenkins.getInstance().getItem(job);
		if (item instanceof Job) {
			return (Job) item;
		}
		return null;
	}

	public static String getRootUrl() {
		// TODO is there a better place to find this?
		String root = Jenkins.getInstance().getRootUrl();
		if (root == null || root.length() == 0) {
			root = "http://localhost:8080/";
		}
		return root;
	}

	public static boolean verifyEnvVars(Map<String, ParameterDefinition> paramMap, WorkflowJob workflowJob) {
        if (paramMap != null) {
            String fullName = workflowJob.getFullName();
            WorkflowJob job = Jenkins.getInstance().getItemByFullName(fullName, WorkflowJob.class);
            if (job == null) {
                // this should not occur if an impersonate call has been made higher up
                // the stack
                LOGGER.warning("A run of workflow job " + workflowJob.getName() + " unexpectantly not saved to disk.");
                return false;
            }
            ParametersDefinitionProperty props = job.getProperty(ParametersDefinitionProperty.class);
            List<String> names = props.getParameterDefinitionNames();
            for (String name : names) {
                if (!paramMap.containsKey(name)) {
                    LOGGER.warning("A run of workflow job " + job.getName() + " was expecting parameter " + name + ", but it is not in the parameter list");
                    return false;
                }
            }
        }
	    return true;
	}

	public static Map<String, ParameterDefinition> addJobParamForBuildEnvs(WorkflowJob job, JenkinsPipelineBuildStrategy strat,
			boolean replaceExisting) throws IOException {
		List<EnvVar> envs = strat.getEnv();
        Map<String, ParameterDefinition> paramMap = null;
		if (envs.size() > 0) {
			// build list of current env var names for possible deletion of env
			// vars currently stored
			// as job params
			List<String> envKeys = new ArrayList<String>();
			for (EnvVar env : envs) {
				envKeys.add(env.getName());
			}
			// get existing property defs, including any manually added from the
			// jenkins console independent of BC
			ParametersDefinitionProperty params = job.removeProperty(ParametersDefinitionProperty.class);
			paramMap = new HashMap<String, ParameterDefinition>();
			// store any existing parameters in map for easy key lookup
			if (params != null) {
				List<ParameterDefinition> existingParamList = params.getParameterDefinitions();
				for (ParameterDefinition param : existingParamList) {
					// if a user supplied param, add
					if (param.getDescription() == null || !param.getDescription().equals(PARAM_FROM_ENV_DESCRIPTION))
						paramMap.put(param.getName(), param);
					else if (envKeys.contains(param.getName())) {
						// the env var still exists on the openshift side so
						// keep
						paramMap.put(param.getName(), param);
					}
				}
			}
			for (EnvVar env : envs) {
				if (replaceExisting) {
					StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), env.getValue(),
							PARAM_FROM_ENV_DESCRIPTION);
					paramMap.put(env.getName(), envVar);
				} else if (!paramMap.containsKey(env.getName())) {
					// if list from BC did not have this parameter, it was added
					// via `oc start-build -e` ... in this
					// case, we have chosen to make the default value an empty
					// string
					StringParameterDefinition envVar = new StringParameterDefinition(env.getName(), "",
							PARAM_FROM_ENV_DESCRIPTION);
					paramMap.put(env.getName(), envVar);
				}
			}
			List<ParameterDefinition> newParamList = new ArrayList<ParameterDefinition>(paramMap.values());
			job.addProperty(new ParametersDefinitionProperty(newParamList));
		}
		// force save here ... seen some timing issues with concurrent job updates and run initiations
		job.save();
		return paramMap;
	}

    public static Map<String, ParameterDefinition> addJobParamForPipelineParameters(WorkflowJob job,
        List<PipelineParameter> params, boolean replaceExisting) throws IOException {
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
            for (PipelineParameter parameter : params) {
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

            for (PipelineParameter param : params) {
                ParameterDefinition jenkinsParam = null;
                switch (param.getType()) {
                    case PIPELINE_PARAMETER_TYPE_STRING:
                        jenkinsParam = new StringParameterDefinition(param.getName(), param.getValue(), param.getDescription());
                        break;
                    case PIPELINE_PARAMETER_TYPE_BOOLEAN:
                        jenkinsParam = new BooleanParameterDefinition(param.getName(), Boolean.valueOf(param.getValue()), param.getDescription());
                        break;
                    default:
                        LOGGER.warning("Parameter type `" + param.getType() + "` is not supported.. skipping...");
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
    public static List<ANTLRException> setJobTriggers(WorkflowJob job, List<PipelineTrigger> triggers) throws IOException {
        job.removeProperty(PipelineTriggersJobProperty.class);

        List<ANTLRException> exceptions = new ArrayList<>();
        if (triggers == null || triggers.size() == 0) {
            return exceptions;
        }

        LOGGER.info(() -> "PipelineTrigger's count is " + triggers.size());

        for (PipelineTrigger pipelineTrigger : triggers) {
            Trigger trigger = null;
            switch (pipelineTrigger.getType()) {
                case PIPELINE_TRIGGER_TYPE_CODE_CHANGE:
                    PipelineTriggerCodeChange codeTrigger = pipelineTrigger.getCodeChange();

                    if (codeTrigger == null || !codeTrigger.getEnabled()) {
                        LOGGER.warning("Trigger type `" + PIPELINE_TRIGGER_TYPE_CODE_CHANGE + "` has empty description or is disabled...");
                        break;
                    }

                    try {
                        trigger = new SCMTrigger(codeTrigger.getPeriodicCheck());

                        LOGGER.info(() -> "Add CodeChangeTrigger.");
                    } catch (ANTLRException exc) {
                        LOGGER.severe("Error processing trigger type `" + PIPELINE_TRIGGER_TYPE_CODE_CHANGE + "`: " + exc);
                        exceptions.add(exc);
                    }

                    break;
                case PIPELINE_TRIGGER_TYPE_CRON:
                    PipelineTriggerCron cronTrigger = pipelineTrigger.getCron();
                    if (cronTrigger == null || !cronTrigger.getEnabled()) {
                        LOGGER.warning("Trigger type `" + PIPELINE_TRIGGER_TYPE_CRON + "` has empty description or is disabled...");
                        break;
                    }

                    try {
                        trigger = new TimerTrigger(cronTrigger.getRule());

                        LOGGER.info(() -> "Add CronTrigger.");
                    } catch (ANTLRException exc) {
                        LOGGER.severe("Error processing trigger type `" + PIPELINE_TRIGGER_TYPE_CRON + "`: " + exc);
                        exceptions.add(exc);
                    }

                    break;
                default:
                    LOGGER.warning("Trigger type `" + pipelineTrigger.getType() + "` is not supported... skipping...");
            }

            if(trigger != null) {
                job.addTrigger(trigger);
            }
        }

//        job.setTriggers(exceptions);
//        job.save();

        LOGGER.info(() -> "Job trigger save done.");

        return exceptions;
    }

	public static List<Action> setJobRunParamsFromEnv(WorkflowJob job, List<PipelineParameter> pipelineParameters,
			List<Action> buildActions) {
		List<String> envKeys = new ArrayList<>();
		List<ParameterValue> envVarList = new ArrayList<>();

		// add any existing job params that were not env vars, using their
		// default values
		ParametersDefinitionProperty params = job.getProperty(ParametersDefinitionProperty.class);
		if (params != null) {
			List<ParameterDefinition> existingParamList = params.getParameterDefinitions();
			for (ParameterDefinition param : existingParamList) {
				if (!envKeys.contains(param.getName())) {
					String type = param.getType();
					switch (type) {
					case "BooleanParameterDefinition":
						BooleanParameterDefinition bpd = (BooleanParameterDefinition) param;
						envVarList.add(bpd.getDefaultParameterValue());
						break;
					case "ChoiceParameterDefintion":
						ChoiceParameterDefinition cpd = (ChoiceParameterDefinition) param;
						envVarList.add(cpd.getDefaultParameterValue());
						break;
					case "CredentialsParameterDefinition":
						CredentialsParameterDefinition crpd = (CredentialsParameterDefinition) param;
						envVarList.add(crpd.getDefaultParameterValue());
						break;
					case "FileParameterDefinition":
						FileParameterDefinition fpd = (FileParameterDefinition) param;
						envVarList.add(fpd.getDefaultParameterValue());
						break;
					// don't currently support since sync-plugin does not claim
					// subversion plugin as a direct dependency
					/*
					 * case "ListSubversionTagsParameterDefinition":
					 * ListSubversionTagsParameterDefinition lpd =
					 * (ListSubversionTagsParameterDefinition)param;
					 * envVarList.add(lpd.getDefaultParameterValue()); break;
					 */
					case "PasswordParameterDefinition":
						PasswordParameterDefinition ppd = (PasswordParameterDefinition) param;
						envVarList.add(ppd.getDefaultParameterValue());
						break;
					case "RunParameterDefinition":
						RunParameterDefinition rpd = (RunParameterDefinition) param;
						envVarList.add(rpd.getDefaultParameterValue());
						break;
					case "StringParameterDefinition":
						StringParameterDefinition spd = (StringParameterDefinition) param;
						envVarList.add(spd.getDefaultParameterValue());
						break;
					default:
						// used to have the following:
						// envVarList.add(new
						// StringParameterValue(param.getName(),
						// (param.getDefaultParameterValue() != null &&
						// param.getDefaultParameterValue().getValue() != null ?
						// param.getDefaultParameterValue().getValue().toString()
						// : "")));
						// but mvn verify complained
						ParameterValue pv = param.getDefaultParameterValue();
						if (pv != null) {
							Object val = pv.getValue();
							if (val != null) {
								envVarList.add(new StringParameterValue(param.getName(), val.toString()));
							}
						}
					}
				}
			}
		}

		if (envVarList.size() > 0) {
            buildActions.add(new ParametersAction(envVarList));
        }

		return buildActions;
	}

	public static List<Action> setJobRunParamsFromEnvAndUIParams(WorkflowJob job, List<PipelineParameter> pipelineParameters,
			List<Action> buildActions, ParametersAction params) {
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

	@NotNull
    public static List<ParameterValue> getParameterValues(List<PipelineParameter> pipelineParameters) {
        List<ParameterValue> envVarList = new ArrayList<>();
        if (pipelineParameters != null && pipelineParameters.size() > 0) {
            for (PipelineParameter pipeParam : pipelineParameters) {
                ParameterValue paramValue = null;
                switch (pipeParam.getType()) {
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
        }

        return envVarList;
    }

    public static boolean triggerJob(WorkflowJob job, Pipeline pipeline)
            throws IOException {
	      LOGGER.info("will trigger pipeline: "+pipeline.getMetadata().getName());
        if (isAlreadyTriggered(job, pipeline)) {
          LOGGER.info("pipeline already triggered: "+pipeline.getMetadata().getName());
            return false;
        }

        String pipelineConfigName = pipeline.getSpec().getPipelineConfig().getName();
        if (isBlank(pipelineConfigName)) {
          LOGGER.info("pipeline has not config: "+pipeline.getMetadata().getName());
            return false;
        }

        PipelineConfigProjectProperty pcProp = job.getProperty(PipelineConfigProjectProperty.class);
        if (pcProp == null) {
            LOGGER.warning("aborting trigger of pipeline " + pipeline
                    + "because of missing pc project property");
            return false;
        }

        ObjectMeta meta = pipeline.getMetadata();
        String namespace = meta.getNamespace();
        PipelineConfig pipelineConfig = getAuthenticatedAlaudaClient()
                .pipelineConfigs().inNamespace(namespace)
                .withName(pipelineConfigName).get();
        if (pipelineConfig == null) {
            LOGGER.info("pipeline config not found....: "+pipeline.getMetadata().getName()+" - config name "+pipelineConfigName);
            return false;
        }

        // sync on intern of name should guarantee sync on same actual obj
        synchronized (pipelineConfig.getMetadata().getUid().intern()) {
          LOGGER.info("pipeline config source credentials: "+pipelineConfig.getMetadata().getName());

            updateSourceCredentials(pipelineConfig);

            // We need to ensure that we do not remove
            // existing Causes from a Run since other
            // plugins may rely on them.
            List<Cause> newCauses = new ArrayList<>();
            newCauses.add(new JenkinsPipelineCause(pipeline, pcProp.getUid()));
            CauseAction originalCauseAction = PipelineToActionMapper
                    .removeCauseAction(pipeline.getMetadata().getName());
            if (originalCauseAction != null) {
                if (LOGGER.isLoggable(Level.FINE)) {
                    LOGGER.fine("Adding existing causes...");
                    for (Cause c : originalCauseAction.getCauses()) {
                        LOGGER.fine("orginal cause: " + c.getShortDescription());
                    }
                }
                newCauses.addAll(originalCauseAction.getCauses());
                if (LOGGER.isLoggable(Level.FINE)) {
                    for (Cause c : newCauses) {
                        LOGGER.fine("new cause: " + c.getShortDescription());
                    }
                }
            }

            List<Action> pipelineActions = new ArrayList<>();
            CauseAction bCauseAction = new CauseAction(newCauses);
            pipelineActions.add(bCauseAction);

            PipelineSourceGit sourceGit = pipeline.getSpec().getSource().getGit();
            String commit = null;
            if (pipeline.getMetadata().getAnnotations() != null &&
              pipeline.getMetadata().getAnnotations().containsKey(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT)) {
              commit = pipeline.getMetadata().getAnnotations().get(ALAUDA_DEVOPS_ANNOTATIONS_COMMIT);
            }
          if (sourceGit != null && commit != null) {
            try {
              URIish repoURL = new URIish(sourceGit.getUri());
              pipelineActions.add(new RevisionParameterAction(commit, repoURL));
            } catch (URISyntaxException e) {
              LOGGER.log(SEVERE, "Failed to parse git repo URL"
                + sourceGit.getUri(), e);
            }
          }

          LOGGER.info("pipeline got cause....: "+pipeline.getMetadata().getName()+" pipeline actions "+pipelineActions);

            // params added by user in jenkins ui
            ParametersAction userProvidedParams = PipelineToActionMapper
                    .removeParameterAction(pipeline.getMetadata().getName());

            pipelineActions = setJobRunParamsFromEnvAndUIParams(job, pipeline.getSpec().getParameters(),
                    pipelineActions, userProvidedParams);

            putJobWithPipelineConfig(job, pipelineConfig);
            LOGGER.info(() -> "pipeline config update with job: "+pipeline.getMetadata().getName()+" pipeline config "+pipelineConfig.getMetadata().getName());

            Action[] actionArray = pipelineActions.toArray(new Action[pipelineActions.size()]);
            if (job.scheduleBuild2(0, actionArray) != null) {

                updatePipelinePhase(pipeline, QUEUED);
                // If builds are queued too quickly, Jenkins can add the cause
                // to the previous queued pipeline so let's add a tiny
                // sleep.
                try {
                    Thread.sleep(50l);
                } catch (InterruptedException e) {
                    // Ignore
                    LOGGER.log(Level.SEVERE, "updatePipelinePhase Interrupted", e);
                    Thread.currentThread().interrupt();
                }
                return true;
            } else {
              LOGGER.info(() -> "Will not schedule build for this pipeline: "+pipeline.getMetadata().getName());
            }

            return false;
        }
    }

	private static boolean isAlreadyTriggered(WorkflowJob job, Pipeline pipeline) {
		return getRun(job, pipeline) != null;
	}

	public synchronized static void cancelPipeline(WorkflowJob job, Pipeline pipeline) {
		cancelPipeline(job, pipeline, false);
	}

	public synchronized static void cancelPipeline(WorkflowJob job, Pipeline pipeline, boolean deleted) {
		if (!cancelQueuedPipeline(job, pipeline)) {
            cancelRunningPipeline(job, pipeline);
		}

		if (deleted) {
			return;
		}

        updatePipelinePhase(pipeline, CANCELLED);
	}

	private static WorkflowRun getRun(WorkflowJob job, Pipeline pipeline) {
		if (pipeline != null && pipeline.getMetadata() != null) {
			return getRun(job, pipeline.getMetadata().getUid());
		}
		return null;
	}

	private static WorkflowRun getRun(WorkflowJob job, String pipelineUid) {
		for (WorkflowRun run : job.getBuilds()) {
			JenkinsPipelineCause cause = run.getCause(JenkinsPipelineCause.class);
			if (cause != null && cause.getUid().equals(pipelineUid)) {
				return run;
			}
		}
		return null;
	}

	public static void deleteRun(WorkflowJob workflowJob, Pipeline pipeline) {
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

	private static boolean cancelRunningPipeline(WorkflowJob job, Pipeline pipeline) {
		String pipelineUid = pipeline.getMetadata().getUid();
		WorkflowRun run = getRun(job, pipelineUid);
		if (run != null && run.isBuilding()) {
			terminateRun(run);
			return true;
		}
		return false;
	}

	private static boolean cancelNotYetStartedPipeline(WorkflowJob job, Pipeline pipeline) {
		String pipelineUid = pipeline.getMetadata().getUid();
		WorkflowRun run = getRun(job, pipelineUid);
		if (run != null && run.hasntStartedYet()) {
			terminateRun(run);
			return true;
		}
		return false;
	}

	private static void cancelNotYetStartedPipeliness(WorkflowJob job, String pcUid) {
		cancelQueuedBuilds(job, pcUid);
		for (WorkflowRun run : job.getBuilds()) {
			if (run != null && run.hasntStartedYet()) {
				JenkinsPipelineCause cause = run.getCause(JenkinsPipelineCause.class);
				if (cause != null && cause.getPipelineConfigUid().equals(pcUid)) {
					terminateRun(run);
				}
			}
		}
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
	public static boolean cancelQueuedPipeline(WorkflowJob job, Pipeline pipeline) {
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
			for (Cause cause : item.getCauses()) {
				if (cause instanceof JenkinsPipelineCause) {
					JenkinsPipelineCause pipelineCause = (JenkinsPipelineCause) cause;
					if (pipelineCause.getPipelineConfigUid().equals(pcUid)) {
						Pipeline pipeline = new PipelineBuilder().withNewMetadata().withNamespace(pipelineCause.getNamespace())
								.withName(pipelineCause.getName()).and().build();
						cancelQueuedPipeline(job, pipeline);
					}
				}
			}
		}
	}

	public static WorkflowJob getJobFromPipeline(Pipeline pipeline) {
		String pipelineConfigName = pipeline.getSpec().getPipelineConfig().getName();
		if (StringUtils.isEmpty(pipelineConfigName)) {
			return null;
		}
		PipelineConfig pipelineConfig = getAuthenticatedAlaudaClient().pipelineConfigs()
				.inNamespace(pipeline.getMetadata().getNamespace()).withName(pipelineConfigName).get();
		if (pipelineConfig == null) {
			return null;
		}
		return getJobFromPipelineConfig(pipelineConfig);
	}

    public static void maybeScheduleNext(WorkflowJob job) {
        PipelineConfigProjectProperty pcp = job.getProperty(PipelineConfigProjectProperty.class);
        if (pcp == null) {
            return;
        }

        // TODO: Change to filter on the API level
        PipelineList list = filterNew(getAuthenticatedAlaudaClient().pipelines()
                .inNamespace(pcp.getNamespace()).withLabel(ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG, pcp.getName()).list());
        LOGGER.info("Got new pipeline list: " + list.getItems());
        handlePipelineList(job, list.getItems(), pcp);
    }

  public static PipelineList filterNew(PipelineList list) {
    if (list == null || list.getItems() == null || list.getItems().size() == 0) {
      return list;
    }
    list.getItems().removeIf(p -> !isNew(p.getStatus()));
    return list;
  }

	public static void handlePipelineList(WorkflowJob job, List<Pipeline> pipelines,
                                        PipelineConfigProjectProperty pipelineConfigProjectProperty) {
		if (pipelines.isEmpty()) {
			return;
		}
		// TODO: not implemented yet
    boolean isSerialLatestOnly = false;
//		boolean isSerialLatestOnly = SERIAL_LATEST_ONLY.equals(pipelineConfigProjectProperty.getPipelineRunPolicy());
//		if (isSerialLatestOnly) {
//			// Try to cancel any pipelines that haven't actually started, waiting
//			// for executor perhaps.
//			cancelNotYetStartedPipeliness(job, pipelineConfigProjectProperty.getUid());
//		}
		sort(pipelines, new Comparator<Pipeline>() {
			@Override
			public int compare(Pipeline p1, Pipeline p2) {
				// Order so cancellations are first in list so we can stop
				// processing build list when build run policy is
				// SerialLatestOnly and job is currently building.
				Boolean p1Cancelled = p1.getStatus() != null && p1.getStatus().getPhase() != null
						? isCancelled(p1.getStatus())
						: false;
				Boolean p2Cancelled = p2.getStatus() != null && p2.getStatus().getPhase() != null
						? isCancelled(p2.getStatus())
						: false;
				// Inverse comparison as boolean comparison would put false
				// before true. Could have inverted both cancellation
				// states but this removes that step.
				int cancellationCompare = p2Cancelled.compareTo(p1Cancelled);
				if (cancellationCompare != 0) {
					return cancellationCompare;
				}

                if (p1.getMetadata().getAnnotations() == null
                        || p1.getMetadata().getAnnotations()
                                .get(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER) == null) {
                    LOGGER.warning(() -> "cannot compare pipeline "
                            + p1.getMetadata().getName()
                            + " from namespace "
                            + p1.getMetadata().getNamespace()
                            + ", has bad annotations: "
                            + p1.getMetadata().getAnnotations());
                    return 0;
                }
                if (p2.getMetadata().getAnnotations() == null
                        || p2.getMetadata().getAnnotations()
                                .get(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER) == null) {
                    LOGGER.warning(() -> "cannot compare pipeline "
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
                                    .get(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER)),
                            Long.parseLong(p2
                                    .getMetadata()
                                    .getAnnotations()
                                    .get(ALAUDA_DEVOPS_ANNOTATIONS_PIPELINE_NUMBER)));
                } catch (Throwable t) {
                    LOGGER.log(Level.FINE, "handlePipelineList", t);
                }
                return rc;
			}
		});
		boolean isSerial = !job.isConcurrentBuild();//PipelineRunPolicy.SERIAL.equals(pipelineConfigProjectProperty.getPipelineRunPolicy());
		boolean jobIsBuilding = job.isBuilding();
		for (int i = 0; i < pipelines.size(); i++) {
			Pipeline p = pipelines.get(i);
			if (!AlaudaUtils.isPipelineStrategyPipeline(p))
				continue;
			// For SerialLatestOnly we should try to cancel all pipelines before
			// the latest one requested.
			if (isSerialLatestOnly) {
				// If the job is currently building, then let's return on the
				// first non-cancellation request so we do not try to
				// queue a new build.
				if (jobIsBuilding && !isCancelled(p.getStatus())) {
					return;
				}

				if (i < pipelines.size() - 1) {
					cancelQueuedPipeline(job, p);
					// TODO: maybe not necessary?
					updatePipelinePhase(p, CANCELLED);
					continue;
				}
			}
			boolean buildAdded = false;
			try {
				buildAdded = addEventToJenkinsJobRun(p);
			} catch (IOException e) {
				ObjectMeta meta = p.getMetadata();
				LOGGER.log(WARNING, "Failed to add new build " + meta.getNamespace() + "/" + meta.getName(), e);
			}
			// If it's a serial build then we only need to schedule the first
			// build request.
			if (isSerial && buildAdded) {
				return;
			}
		}
	}

	public static String getFullJobName(WorkflowJob job) {
		return job.getRelativeNameFrom(Jenkins.getInstance());
	}

	public static String getBuildConfigName(WorkflowJob job) {
		String name = getFullJobName(job);
		GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
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
}
