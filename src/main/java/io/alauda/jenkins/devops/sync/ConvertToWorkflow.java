package io.alauda.jenkins.devops.sync;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.model.ParameterDefinition;
import hudson.util.XStream2;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;
import io.alauda.jenkins.devops.sync.constants.PipelineRunPolicy;
import io.alauda.jenkins.devops.sync.controller.PipelineConfigController;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.kubernetes.client.models.V1ObjectMeta;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND_MULTI_BRANCH;

@Extension
public class ConvertToWorkflow implements PipelineConfigConvert<WorkflowJob> {
    private final Logger logger = Logger.getLogger(ConvertToWorkflow.class.getName());

    @Override
    public boolean accept(V1alpha1PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            return false;
        }

        Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
        return (labels == null || !PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
    }

    @Override
    public WorkflowJob convert(V1alpha1PipelineConfig pipelineConfig) throws IOException {
        String jobName = AlaudaUtils.jenkinsJobName(pipelineConfig);
        String jobFullName = AlaudaUtils.jenkinsJobFullName(pipelineConfig);
        String namespace = pipelineConfig.getMetadata().getNamespace();
        String name = pipelineConfig.getMetadata().getName();
        String resourceVer = pipelineConfig.getMetadata().getResourceVersion();
        WorkflowJob job = PipelineConfigToJobMap.getJobFromPipelineConfig(pipelineConfig);
        Jenkins activeInstance = Jenkins.getInstance();
        formatJenkinsfile(pipelineConfig);
        ItemGroup parent = activeInstance;
        if (job == null) {
            job = (WorkflowJob) activeInstance.getItemByFullName(jobFullName);
        }

        boolean newJob = job == null;
        if (newJob) {
            parent = AlaudaUtils.getOrCreateFullNameParent(activeInstance, jobFullName, namespace);
            job = new WorkflowJob(parent, jobName);
            job.addProperty(WorkflowJobProperty.getInstance(pipelineConfig));
        } else {
            WorkflowJobProperty wfJobProperty = job.getProperty(WorkflowJobProperty.class);
            if(wfJobProperty == null) {
                logger.warning("Missing the AlaudaWorkflowJobProperty, try to find a old property.");

                PipelineConfigProjectProperty pcpp = job.getProperty(PipelineConfigProjectProperty.class);
                if(pcpp == null) {
                    logger.warning("No old property PipelineConfigProjectProperty.");
                    return null;
                }

                wfJobProperty = pcpp;
            }

            if(isSameJob(pipelineConfig, wfJobProperty)){
                // only could update the resourceVersion
                wfJobProperty.setResourceVersion(resourceVer);
            } else {
                logger.warning(String.format("Not the same job, can't handle it." +
                        "PipelineConfig uid is %s, job uid is %s",
                        pipelineConfig.getMetadata().getUid(), wfJobProperty.getUid()));
                return null;
            }
        }

        job.setDisplayName(AlaudaUtils.jenkinsJobDisplayName(pipelineConfig));

        FlowDefinition flowDefinition = PipelineConfigToJobMapper.mapPipelineConfigToFlow(pipelineConfig);
        if (flowDefinition == null) {
            updatePipelineConfigPhase(pipelineConfig);
            return null;
        }
        job.setDefinition(flowDefinition);
        job.setConcurrentBuild(!PipelineRunPolicy.SERIAL.equals(pipelineConfig.getSpec().getRunPolicy()));

        // (re)populate job param list with any parameters
        // from the PipelineConfig
        Map<String, ParameterDefinition> paramMap = JenkinsUtils.addJobParamForPipelineParameters(job,
                pipelineConfig.getSpec().getParameters(), true);

        // Setting triggers according to pipeline config
        List<ANTLRException> triggerExceptions = JenkinsUtils.setJobTriggers(job, pipelineConfig.getSpec().getTriggers());
        triggerExceptions.forEach(ex -> {
            V1alpha1Condition condition = new V1alpha1Condition();
            condition.setReason(ErrorMessages.INVALID_TRIGGER);
            condition.setMessage(ex.getMessage());
            pipelineConfig.getStatus().getConditions().add(condition);
        });

        InputStream jobStream = new StringInputStream(new XStream2().toXML(job));
        if (newJob) {
            try {
                if (parent instanceof Folder) {
                    Folder folder = (Folder) parent;
                    folder.createProjectFromXML(jobName, jobStream).save();
                } else {
                    activeInstance.createProjectFromXML(jobName, jobStream).save();
                }

                logger.info("Created job " + jobName + " from PipelineConfig " + namespace + "/" + name
                        + " with revision: " + resourceVer);
            } catch (IllegalArgumentException e) {
                // jenkins might reload existing jobs on
                // startup between the
                // newJob check above and when we make
                // the createProjectFromXML call; if so,
                // retry as an update
                updateJob(job, jobStream, jobName, pipelineConfig);
            }
        } else {
            updateJob(job, jobStream, jobName, pipelineConfig);
        }

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
            logger.warning("Could not find created job " + fullName + " for PipelineConfig: " + namespace + "/" + name);
        } else {
            updatePipelineConfigPhase(pipelineConfig);

            JenkinsUtils.verifyEnvVars(paramMap, workflowJob);
            PipelineConfigToJobMap.putJobWithPipelineConfig(workflowJob, pipelineConfig);
        }

        return workflowJob;
    }

    private void formatJenkinsfile(final V1alpha1PipelineConfig pipelineConfig) {
        String jenkinsfile = pipelineConfig.getSpec().getStrategy().getJenkins().getJenkinsfile();
        if (StringUtils.isEmpty(jenkinsfile)) {
            return;
        }

        String formattedJenkinsfile = null;
        try {
            formattedJenkinsfile = JenkinsUtils.formatJenkinsfile(jenkinsfile);
        } catch (IOException e) {
            // format error, could be pipeline syntax error
            logger.log(Level.WARNING, "Failed to format Pipeline.", e);
        } catch (MultipleCompilationErrorsException e) {
            logger.log(Level.WARNING, "Pipeline syntax has errors.", e);
        }

        if (StringUtils.isNotEmpty(formattedJenkinsfile)) {
            V1ObjectMeta metadata = pipelineConfig.getMetadata();
            String name = metadata.getName();

            V1alpha1PipelineConfig oldPipelineConfig = DeepCopyUtils.deepCopy(pipelineConfig);
            pipelineConfig.getSpec().getStrategy().getJenkins().jenkinsfile(formattedJenkinsfile);

            PipelineConfigController.updatePipelineConfig(oldPipelineConfig, pipelineConfig);

            logger.fine(String.format("Format PipelineConfig's jenkinsfile %s, name: %s", formattedJenkinsfile, name));
        }
    }
}
