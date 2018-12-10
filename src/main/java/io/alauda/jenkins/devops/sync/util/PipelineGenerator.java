package io.alauda.jenkins.devops.sync.util;

import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.kubernetes.api.model.*;
import jenkins.branch.Branch;
import jenkins.branch.BranchProperty;
import jenkins.scm.api.SCMHead;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import static io.alauda.jenkins.devops.sync.util.AlaudaUtils.getAuthenticatedAlaudaClient;

public abstract class PipelineGenerator {

    private static final Logger LOGGER = Logger.getLogger(PipelineGenerator.class.getName());
    private static String TRIGGER_BY = "Triggered by Jenkins job at ";

    public static Pipeline buildPipeline(PipelineConfig config, List<Action> actions) {
        return buildPipeline(config, null, actions);
    }

    public static Pipeline buildPipeline(PipelineConfig config, @NotNull WorkflowJob job,
                                         String triggerURL, List<Action> actions) {
        ItemGroup parent = job.getParent();
        Map<String, String> annotations = new HashMap<>();
        if(parent instanceof WorkflowMultiBranchProject) {
            BranchJobProperty property = job.getProperty(BranchJobProperty.class);
            if(property != null) {
                Branch branch = property.getBranch();
                annotations.put(Constants.MULTI_BRANCH_NAME, branch.getEncodedName());

                SCMHead head = SCMHead.HeadByItem.findHead(job);

                // TODO need to consider multi-tag like GitTagSCMHead
                if(isPR(job)) {
                    annotations.put(Constants.MULTI_BRANCH_CATEGORY, "pr");
                } else {
                    annotations.put(Constants.MULTI_BRANCH_CATEGORY, "branch");
                }
            }
        }

        return buildPipeline(config, annotations, triggerURL, actions);
    }

    public static boolean isPR(Item item) {
        SCMHead head = SCMHead.HeadByItem.findHead(item);
        if(head == null) {
            return false;
        }

        String headClsName = head.getClass().getName();
        return "org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead".equals(headClsName)
                || "com.cloudbees.jenkins.plugins.bitbucket.BranchSCMHead".equals(headClsName);
    }


    @Deprecated
    public static Pipeline buildPipeline(PipelineConfig config, String triggerURL, List<Action> actions) {
        return buildPipeline(config, new HashMap<>(), triggerURL, actions);
    }

    public static Pipeline buildPipeline(PipelineConfig config, Map<String, String> annotations, String triggerURL, List<Action> actions) {
        PipelineSpec pipelineSpec = buildPipelineSpec(config, triggerURL);

        // TODO here should be multi-cause, fix later
        String cause = null;
        for (Action action : actions) {
            if (!(action instanceof CauseAction)) {
                continue;
            }

            // TODO just get first causeAction for now
            CauseAction causeAction = (CauseAction) action;
            if (cause == null) {
                if (causeAction.findCause(SCMTrigger.SCMTriggerCause.class) != null) {
                    cause = PIPELINE_TRIGGER_TYPE_CODE_CHANGE;
                } else if (causeAction.findCause(TimerTrigger.TimerTriggerCause.class) != null) {
                    cause = PIPELINE_TRIGGER_TYPE_CRON;
                }
            } else {
                LOGGER.fine("CauseAction is : " + causeAction.getDisplayName());
            }
        }

        // we think of the default cause is manual
        if (cause == null) {
            cause = PIPELINE_TRIGGER_TYPE_MANUAL;
        }

        PipelineCause pipelineCause = new PipelineCause(TRIGGER_BY + triggerURL, cause);
        pipelineSpec.setCause(pipelineCause);

        // add parameters
        for(Action action : actions) {
            if(!(action instanceof ParametersAction)) {
                continue;
            }

            ParametersAction paramAction = (ParametersAction) action;
            if(paramAction.getParameters() == null) {
                continue;
            }

            List<PipelineParameter> parameters = new ArrayList<>();

            for(ParameterValue param : paramAction.getParameters()) {
                PipelineParameter pipeParam = ParameterUtils.to(param);
                if(pipeParam != null) {
                    parameters.add(pipeParam);
                }
            }

            pipelineSpec.setParameters(parameters);
        }

        String namespace = config.getMetadata().getNamespace();

        // update pipeline to k8s
        return getAuthenticatedAlaudaClient()
            .pipelines()
            .inNamespace(namespace)
            .createNew()
            .withNewMetadata().addToAnnotations(annotations)
            .withName(config.getMetadata().getName())
            .withNamespace(namespace)
            .endMetadata()
            .withSpec(pipelineSpec)
            .done();
    }

    public static PipelineSpec buildPipelineSpec(PipelineConfig config) {
        return buildPipelineSpec(config, null);
    }

    private static PipelineSpec buildPipelineSpec(PipelineConfig config, String triggerURL) {
        PipelineSpec pipeSpec = new PipelineSpec();
        PipelineConfigSpec spec = config.getSpec();
        pipeSpec.setPipelineConfig(new LocalObjectReference(config.getMetadata().getName()));
        pipeSpec.setJenkinsBinding(spec.getJenkinsBinding());
        pipeSpec.setRunPolicy(spec.getRunPolicy());
        pipeSpec.setTriggers(spec.getTriggers());
        pipeSpec.setStrategy(spec.getStrategy());
        pipeSpec.setHooks(spec.getHooks());
        pipeSpec.setSource(spec.getSource());
        return pipeSpec;
    }
}
