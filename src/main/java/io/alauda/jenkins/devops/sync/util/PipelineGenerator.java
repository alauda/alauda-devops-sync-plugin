package io.alauda.jenkins.devops.sync.util;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import com.cloudbees.jenkins.plugins.bitbucket.PullRequestSCMHead;
import hudson.model.Action;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.triggers.SCMTrigger;
import hudson.triggers.TimerTrigger;
import io.alauda.devops.java.client.models.V1alpha1LocalObjectReference;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineBuilder;
import io.alauda.devops.java.client.models.V1alpha1PipelineCause;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigSpec;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.models.V1alpha1PipelineSpec;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.multiBranch.PullRequest;
import io.jenkins.plugins.gitlabbranchsource.MergeRequestSCMHead;
import io.kubernetes.client.models.V1ObjectMetaBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.annotation.Nonnull;
import jenkins.branch.Branch;
import jenkins.branch.BranchIndexingCause;
import jenkins.scm.api.SCMHead;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import jenkins.scm.api.mixin.ChangeRequestSCMHead;
import jenkins.scm.api.mixin.ChangeRequestSCMHead2;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.cps.replay.ReplayCause;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

public abstract class PipelineGenerator {

  private static final Logger LOGGER = Logger.getLogger(PipelineGenerator.class.getName());
  private static String TRIGGER_BY = "Triggered by Jenkins job at ";

  public static V1alpha1Pipeline buildPipeline(
      V1alpha1PipelineConfig config,
      @Nonnull WorkflowJob job,
      String triggerURL,
      List<Action> actions) {
    ItemGroup parent = job.getParent();
    Map<String, String> annotations = new HashMap<>();
    if (parent instanceof WorkflowMultiBranchProject) {
      addBranchSCMToAnnotations(job, annotations);
    }

    return buildPipeline(config, annotations, triggerURL, actions);
  }

  public static void addBranchSCMToAnnotations(
      @Nonnull WorkflowJob job, Map<String, String> annotations) {
    BranchJobProperty property = job.getProperty(BranchJobProperty.class);
    if (property != null) {
      Branch branch = property.getBranch();
      annotations.put(Annotations.MULTI_BRANCH_NAME.get().toString(), branch.getName());

      String scmURL = "";
      ObjectMetadataAction metadataAction = job.getAction(ObjectMetadataAction.class);
      if (metadataAction != null) {
        scmURL = metadataAction.getObjectUrl();
      }

      PullRequest pr = getPR(job);
      if (pr != null) {
        pr.setUrl(scmURL);
        annotations.put(Annotations.MULTI_BRANCH_CATEGORY.get().toString(), "pr");
        annotations.put(
            Annotations.MULTI_BRANCH_PR_DETAIL.get().toString(),
            JSONObject.fromObject(pr).toString());
      } else {
        annotations.put(Annotations.MULTI_BRANCH_CATEGORY.get().toString(), "branch");
      }
    }
  }

  public static PullRequest getPR(Item item) {
    PullRequest pr = null;
    SCMHead head = SCMHead.HeadByItem.findHead(item);
    if (!(head instanceof ChangeRequestSCMHead)) {
      return pr;
    }

    pr = new PullRequest();
    ChangeRequestSCMHead prHead = (ChangeRequestSCMHead) head;
    pr.setTargetBranch(prHead.getTarget().getName());
    pr.setId(prHead.getId());

    if (head instanceof ChangeRequestSCMHead2) {
      pr.setSourceBranch(((ChangeRequestSCMHead2) head).getOriginName());
    }

    setupTitle(pr, prHead);

    return pr;
  }

  private static void setupTitle(PullRequest pr, ChangeRequestSCMHead prHead) {
    String title = "";
    if (prHead instanceof MergeRequestSCMHead) {
      title = ((MergeRequestSCMHead) prHead).getTitle();
    } else if (prHead instanceof PullRequestSCMHead) {
      title = ((PullRequestSCMHead) prHead).getTitle();
    } else if (prHead instanceof org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead) {
      title = ((org.jenkinsci.plugins.github_branch_source.PullRequestSCMHead) prHead).getTitle();
    }
    pr.setTitle(title);
  }

  /**
   * Convert a cause object into name
   *
   * @param cause cause object
   * @return cause name
   */
  public static String causeConvert(Cause cause) {
    LOGGER.log(Level.FINE, "causeConvert from " + cause.getClass());

    String causeName;
    if (cause instanceof SCMTrigger.SCMTriggerCause) {
      causeName = PIPELINE_TRIGGER_TYPE_CODE_CHANGE;
    } else if (cause instanceof TimerTrigger.TimerTriggerCause) {
      causeName = PIPELINE_TRIGGER_TYPE_CRON;
    } else if (cause instanceof BranchIndexingCause) {
      causeName = PIPELINE_TRIGGER_TYPE_BRANCH_SCAN;
    } else if (cause instanceof Cause.UpstreamCause) {
      causeName = PIPELINE_TRIGGER_TYPE_UPSTREAM_CAUSE;
    } else if (cause instanceof ReplayCause) {
      causeName = PIPELINE_TRIGGER_TYPE_REPLAY_CAUSE;
    } else {
      causeName = PIPELINE_TRIGGER_TYPE_UNKNOWN_CAUSE;
    }
    return causeName;
  }

  private static V1alpha1Pipeline buildPipeline(
      V1alpha1PipelineConfig config,
      Map<String, String> annotations,
      String triggerURL,
      List<Action> actions) {
    V1alpha1PipelineSpec pipelineSpec = buildPipelineSpec(config, triggerURL);

    List<Cause> allCauses = new ArrayList<>();
    for (Action action : actions) {
      if (!(action instanceof CauseAction)) {
        continue;
      }

      CauseAction causeAction = (CauseAction) action;
      allCauses.addAll(causeAction.getCauses());
    }

    String cause;
    if (allCauses.size() > 1) {
      cause = PIPELINE_TRIGGER_TYPE_MULTI_CAUSES;
      Set<String> allCauseDetails = new HashSet<>();
      allCauses.forEach(
          item -> {
            allCauseDetails.add(causeConvert(item));
          });
      annotations.put(
          ALAUDA_DEVOPS_ANNOTATIONS_CAUSES_DETAILS.get().toString(),
          JSONArray.fromObject(allCauseDetails).toString());
    } else if (allCauses.size() == 1) {
      cause = causeConvert(allCauses.get(0));
    } else {
      // should not be here
      cause = PIPELINE_TRIGGER_TYPE_NOT_FOUND;
    }

    V1alpha1PipelineCause pipelineCause =
        new V1alpha1PipelineCause().type(cause).message(TRIGGER_BY + triggerURL);
    pipelineSpec.setCause(pipelineCause);

    // add parameters
    for (Action action : actions) {
      if (!(action instanceof ParametersAction)) {
        continue;
      }

      ParametersAction paramAction = (ParametersAction) action;
      if (paramAction.getParameters() == null) {
        continue;
      }

      List<V1alpha1PipelineParameter> parameters = new ArrayList<>();

      for (ParameterValue param : paramAction.getParameters()) {
        V1alpha1PipelineParameter pipeParam = ParameterUtils.to(param);
        if (pipeParam != null) {
          parameters.add(pipeParam);
        }
      }

      pipelineSpec.setParameters(parameters);
    }

    // mark this pipeline created by Jenkins
    Map<String, String> labels = new HashMap<>();
    labels.put(Constants.PIPELINE_CREATED_BY, Constants.ALAUDA_SYNC_PLUGIN);

    String namespace = config.getMetadata().getNamespace();
    // update pipeline to k8s

    V1alpha1Pipeline pipe =
        new V1alpha1PipelineBuilder()
            .withMetadata(
                new V1ObjectMetaBuilder()
                    // use generateName field to generate pipeline name
                    // we should not set field of Name
                    .withGenerateName(config.getMetadata().getName())
                    .withNamespace(namespace)
                    .addToAnnotations(annotations)
                    .addToLabels(labels)
                    .build())
            .withSpec(pipelineSpec)
            .build();

    return Clients.get(V1alpha1Pipeline.class).create(pipe);
  }

  public static V1alpha1PipelineSpec buildPipelineSpec(V1alpha1PipelineConfig config) {
    return buildPipelineSpec(config, null);
  }

  private static V1alpha1PipelineSpec buildPipelineSpec(
      V1alpha1PipelineConfig config, String triggerURL) {
    V1alpha1PipelineSpec pipeSpec = new V1alpha1PipelineSpec();
    V1alpha1PipelineConfigSpec spec = config.getSpec();
    pipeSpec.setPipelineConfig(
        new V1alpha1LocalObjectReference().name(config.getMetadata().getName()));
    pipeSpec.setJenkinsBinding(spec.getJenkinsBinding());
    pipeSpec.setRunPolicy(spec.getRunPolicy());
    pipeSpec.setHooks(spec.getHooks());
    pipeSpec.setSource(spec.getSource());
    return pipeSpec;
  }
}
