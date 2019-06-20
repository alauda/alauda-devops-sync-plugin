package io.alauda.jenkins.devops.sync.util;

import hudson.model.*;
import hudson.util.RunList;
import io.alauda.devops.java.client.models.V1alpha1Pipeline;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.controller.PipelineController;
import io.kubernetes.client.models.V1ObjectMeta;
import io.kubernetes.client.models.V1Status;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG;

public class PipelineUtils {
    private static final Logger logger = Logger.getLogger(PipelineUtils.class.getName());


    /**
     * Find all AlaudaPipelineCauses from the Actionable object
     * @param actionable which could holds the actions
     * @return a set of JenkinsPipelineCauses
     */
    public static TreeSet<JenkinsPipelineCause> findAllAlaudaCauses(Actionable actionable) {
        TreeSet<JenkinsPipelineCause> pipelineCauses = new TreeSet<>((a, b) -> {
            if(a == null || a.getName() == null) {
                return 1;
            }
            if(b == null || b.getName() == null) {
                return -1;
            }

            return a.getName().compareTo(b.getName());
        });

        if(actionable == null) {
            return pipelineCauses;
        }

        List<Cause> causes = new ArrayList<>();
        List<CauseAction> causeActions = actionable.getActions(CauseAction.class);
        for(CauseAction causeAction : causeActions) {
            causes.addAll(causeAction.getCauses());
        }
        for(Action action : actionable.getAllActions()) {
            if(action instanceof CauseAction) {
                causes.addAll(((CauseAction) action).getCauses());
            }
        }
        if(actionable instanceof Run) {
            causes.addAll(((Run<?, ?>) actionable).getCauses());
        }
        for(Cause cause : causes) {
            if(cause instanceof JenkinsPipelineCause) {
                pipelineCauses.add((JenkinsPipelineCause) cause);
            }
        }
        return pipelineCauses;
    }


    /**
     * All job build caused by Alauda which will hold JenkinsPipelineCause.
     * A build possible has multiple causes, but we just need the perfect one(first one).
     * @param actionable actionable object
     * @return JenkinsPipelineCause which will be null if cannot find it
     */
    public static JenkinsPipelineCause findAlaudaCause(Actionable actionable) {
        TreeSet<JenkinsPipelineCause> pipelineCauses = findAllAlaudaCauses(actionable);

        return pipelineCauses.isEmpty() ? null : pipelineCauses.first();
    }

    public static V1Status delete(String namespace, String name) {
        return PipelineController.deletePipeline(namespace, name);
    }

    public static void pipelinesCheck(V1alpha1PipelineConfig config) {
        V1ObjectMeta configMetadata = config.getMetadata();
        String namespace = configMetadata.getNamespace();

        List<V1alpha1Pipeline> pipelines = PipelineController.getCurrentPipelineController().listPipelines(namespace)
                .stream().filter(p -> configMetadata.getName().equals(p.getMetadata().getLabels().get(ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG)))
                .collect(Collectors.toList());


        WorkflowJob job = PipelineConfigToJobMap.getJobFromPipelineConfig(config);
        if (job == null) {
            return;
        }

        pipelines.forEach(pipeline -> {
            String uid = pipeline.getMetadata().getUid();
            RunList<WorkflowRun> runList = job.getBuilds().filter(run -> {
                JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);

                return cause != null && cause.getUid().equals(uid);// && phase.equals(pipeline.getStatus().getPhase());
            });//.isEmpty();

            if (runList.isEmpty()) {
                if (PipelinePhases.QUEUED.equals(pipeline.getStatus().getPhase())) {
                    Map<String, String> labels = pipeline.getMetadata().getLabels();
                    String retry = null;
                    if (labels != null) {
                        retry = labels.get("retry");
                    }

                    if (retry == null) {
                        retry = "1";
                    } else {
                        try {
                            retry = String.valueOf(Integer.parseInt(retry) + 1);
                        } catch (NumberFormatException e) {
                            retry = "1";
                        }
                    }

                    PipelineController.addPipelineToNoPCList(pipeline);
                    V1alpha1Pipeline newPipeline = DeepCopyUtils.deepCopy(pipeline);

                    newPipeline.getMetadata().putLabelsItem("retry", retry);
                    PipelineController.updatePipeline(pipeline, newPipeline);

                } else {
                    PipelineController.deletePipeline(namespace, pipeline.getMetadata().getName());
                }
            } else {
                WorkflowRun build = runList.getLastBuild();
                String phase = runToPipelinePhase(build);

                if (!phase.equals(pipeline.getStatus().getPhase())) {
                    V1alpha1Pipeline newPipeline = DeepCopyUtils.deepCopy(pipeline);
                    newPipeline.getStatus().phase(phase);

                    PipelineController.updatePipeline(pipeline, newPipeline);
                }
            }
        });
    }

    public static String runToPipelinePhase(Run run) {
        if (run != null && !run.hasntStartedYet()) {
            if (run.isBuilding()) {
                return PipelinePhases.RUNNING;
            } else {
                Result result = run.getResult();
                if (result != null) {
                    if (result.equals(Result.SUCCESS)) {
                        return PipelinePhases.COMPLETE;
                    } else if (result.equals(Result.ABORTED)) {
                        return PipelinePhases.CANCELLED;
                    } else if (result.equals(Result.FAILURE)) {
                        return PipelinePhases.FAILED;
                    } else if (result.equals(Result.UNSTABLE)) {
                        return PipelinePhases.FAILED;
                    } else {
                        return PipelinePhases.QUEUED;
                    }
                }
            }
        }
        return PipelinePhases.PENDING;
    }
}
