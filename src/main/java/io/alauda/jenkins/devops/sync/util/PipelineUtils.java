package io.alauda.jenkins.devops.sync.util;

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

import java.util.Map;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_LABELS_PIPELINE_CONFIG;

public class PipelineUtils {
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
                JenkinsPipelineCause cause = run.getCause(JenkinsPipelineCause.class);


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
