package io.alauda.jenkins.devops.sync.util;

import hudson.model.Action;
import hudson.model.Actionable;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Result;
import hudson.model.Run;
import hudson.util.RunList;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.JenkinsPipelineCause;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.jenkins.devops.sync.watcher.PipelineWatcher;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineList;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG;

public class PipelineUtils {
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

    public static boolean delete(String namespace, String name) {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return false;
        }

        return client.pipelines().inNamespace(namespace).withName(name).delete();
    }

    public static void pipelinesCheck(PipelineConfig config) {
        ObjectMeta configMetadata = config.getMetadata();
        String namespace = configMetadata.getNamespace();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return;
        }

        PipelineList list = client.pipelines().inNamespace(namespace)
                .withLabel(ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG, configMetadata.getName())
                .list();
        if(list == null) {
            return;
        }

        WorkflowJob job = PipelineConfigToJobMap.getJobFromPipelineConfig(config);
        if(job == null) {
            return;
        }

        list.getItems().forEach(pipeline -> {
            String uid = pipeline.getMetadata().getUid();
            RunList<WorkflowRun> runList = job.getBuilds().filter(run -> {
                JenkinsPipelineCause cause = PipelineUtils.findAlaudaCause(run);

                return cause != null && cause.getUid().equals(uid);// && phase.equals(pipeline.getStatus().getPhase());
            });//.isEmpty();

            if(runList.isEmpty()) {
                if(PipelinePhases.QUEUED.equals(pipeline.getStatus().getPhase())){
                    Map<String, String> labels = pipeline.getMetadata().getLabels();
                    String retry = null;
                    if(labels != null) {
                        retry = labels.get("retry");
                    }

                    if(retry == null) {
                        retry = "1";
                    } else {
                        try{
                            retry = String.valueOf(Integer.parseInt(retry) + 1);
                        } catch (NumberFormatException e) {
                            retry = "1";
                        }
                    }

                    PipelineWatcher.addPipelineToNoPCList(pipeline);

                    client.pipelines().inNamespace(namespace)
                            .withName(pipeline.getMetadata().getName())
                            .edit().editMetadata().addToLabels("retry", retry).endMetadata()
                            .done();
                } else {
                    client.pipelines().inNamespace(namespace)
                            .withName(pipeline.getMetadata().getName()).delete();
                }
            } else {
                WorkflowRun build = runList.getLastBuild();
                String phase = runToPipelinePhase(build);

                if(!phase.equals(pipeline.getStatus().getPhase())) {
                    client.pipelines().inNamespace(namespace)
                            .withName(pipeline.getMetadata().getName())
                            .edit().editStatus()
                            .withPhase(phase)
                            .endStatus().done();
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
