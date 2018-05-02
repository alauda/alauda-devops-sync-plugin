package io.alauda.jenkins.devops.sync;

import hudson.model.Action;
import hudson.model.CauseAction;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.alauda.kubernetes.api.model.*;

import java.util.List;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.AlaudaUtils.getAuthenticatedAlaudaClient;
import static io.alauda.jenkins.devops.sync.Constants.PIPELINE_TRIGGER_TYPE_CODE_CHANGE;
import static io.alauda.jenkins.devops.sync.Constants.PIPELINE_TRIGGER_TYPE_CRON;
import static io.alauda.jenkins.devops.sync.Constants.PIPELINE_TRIGGER_TYPE_MANUAL;

public class PipelineGenerator {

  private static final Logger LOGGER = Logger.getLogger(PipelineGenerator.class.getName());

  private static String TRIGGER_BY = "Triggered by Jenkins job at ";

  public static Pipeline buildPipeline(PipelineConfig config, List<Action> actions) {
    return buildPipeline(config, null, actions);
  }

  public static Pipeline buildPipeline(PipelineConfig config, String triggerURL, List<Action> actions) {
    PipelineSpec pipelineSpec = buildPipelineSpec(config, triggerURL);

    // TODO here should be multi-cause, fix later
    String cause = null;
    for(Action action : actions) {
      if(!(action instanceof CauseAction)) {
        continue;
      }

      // TODO just get first causeAction for now
      CauseAction causeAction = (CauseAction) action;
      if(cause == null) {
        if(causeAction.findCause(SCMTrigger.SCMTriggerCause.class) != null) {
          cause = PIPELINE_TRIGGER_TYPE_CODE_CHANGE;
        } else if(causeAction.findCause(TimerTrigger.TimerTriggerCause.class) != null) {
          cause = PIPELINE_TRIGGER_TYPE_CRON;
        }
      } else {
        LOGGER.fine("CauseAction is : " + causeAction.getDisplayName());
      }
    }

    // we think of the default cause is manual
    if(cause == null) {
      cause = PIPELINE_TRIGGER_TYPE_MANUAL;
    }

    PipelineCause pipelineCause = new PipelineCause(TRIGGER_BY + triggerURL, cause);
    pipelineSpec.setCause(pipelineCause);

    String namespace = config.getMetadata().getNamespace();

    // update pipeline to k8s
    return getAuthenticatedAlaudaClient()
      .pipelines()
      .inNamespace(namespace)
      .createNew()
      .withNewMetadata()
      .withName(config.getMetadata().getName())
      .withNamespace(namespace)
      .endMetadata()
      .withSpec(pipelineSpec)
      .done();
  }

  public static PipelineSpec buildPipelineSpec(PipelineConfig config) {
    return buildPipelineSpec(config, null);
  }

  public static PipelineSpec buildPipelineSpec(PipelineConfig config, String triggerURL) {
    PipelineSpec pipeSpec = new PipelineSpec();
    PipelineConfigSpec spec = config.getSpec();
    pipeSpec.setPipelineConfig(new LocalObjectReference(config.getMetadata().getName()));
    pipeSpec.setJenkinsBinding(spec.getJenkinsBinding());
    pipeSpec.setRunPolicy(spec.getRunPolicy());
    pipeSpec.setTriggers(spec.getTriggers());
    pipeSpec.setStrategy(spec.getStrategy());
    pipeSpec.setParameters(spec.getParameters());
    pipeSpec.setHooks(spec.getHooks());
    pipeSpec.setSource(spec.getSource());
    return pipeSpec;
  }
}
