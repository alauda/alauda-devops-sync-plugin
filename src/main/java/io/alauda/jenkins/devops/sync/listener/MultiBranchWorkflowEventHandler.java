package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import jenkins.branch.MultiBranchProject;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_BRANCH;
import static io.alauda.jenkins.devops.sync.constants.Annotations.MULTI_BRANCH_STALE_BRANCH;

@Extension
public class MultiBranchWorkflowEventHandler implements ItemEventHandler {
    private static final Logger logger = Logger.getLogger(MultiBranchWorkflowEventHandler.class.getName());

    @Override
    public boolean accept(Item item) {
        if(item == null) {
            return false;
        }

        ItemGroup<? extends Item> parent = item.getParent();
        return (parent instanceof MultiBranchProject);
    }

    @Override
    public void onCreated(Item item) {
        BranchJobProperty pro = ((WorkflowJob) item).getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getEncodedName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            addBranchAnnotation(parent, name);
        }
    }

    @Override
    public void onUpdated(Item item) {
        WorkflowJob wfJob = (WorkflowJob) item;

        BranchJobProperty pro = wfJob.getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getEncodedName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            if(wfJob.isDisabled()) {
                // it's a stale pipeline for multi-branch
                delBranchAnnotation(parent, name);
                addStaleAnnotation(parent, name);
            } else {
                // when the deleted branch had been restored
                delStaleBranchAnnotation(parent, name);
                addBranchAnnotation(parent, name);
            }
        }
    }

    @Override
    public void onDeleted(Item item) {
        BranchJobProperty pro = ((WorkflowJob) item).getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getEncodedName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            delStaleBranchAnnotation(parent, name);
        }
    }

    private void addStaleAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.warning("Can't get devops client.");
            return;
        }

        PipelineConfig pc = client.pipelineConfigs().inNamespace(namespace).withName(name).get();
        if(pc == null) {
            logger.warning(String.format("Can't find PipelineConfig by namespace: %s, name: %s.", namespace, name));
            return;
        }

        addStaleBranchAnnotation(pc, branchName);
        updatePipelineConfig(client, namespace, name, pc);
    }

    private void addBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.warning("Can't get devops client.");
            return;
        }

        PipelineConfig pc = client.pipelineConfigs().inNamespace(namespace).withName(name).get();
        if(pc == null) {
            logger.warning(String.format("Can't find PipelineConfig by namespace: %s, name: %s.", namespace, name));
            return;
        }

        addBranchAnnotation(pc, branchName);
        updatePipelineConfig(client, namespace, name, pc);
    }

    private void addBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        addBranchAnnotation(pc, MULTI_BRANCH_BRANCH, name);
    }

    private void addStaleBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        addBranchAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, name);
    }

    private void addBranchAnnotation(@NotNull PipelineConfig pc, final String annotation, String name) {
        ObjectMeta meta = pc.getMetadata();
        Map<String, String> annotations = meta.getAnnotations();
        if(annotations == null) {
            annotations = new HashMap();
            meta.setAnnotations(annotations);
        }

        JSONArray jsonArray;
        String branchJson = annotations.get(annotation);
        if(branchJson == null) {
            jsonArray = new JSONArray();
        } else {
            try {
                jsonArray = JSONArray.fromObject(branchJson);
            } catch (JSONException e) {
                e.printStackTrace();
                jsonArray = new JSONArray();
            }
        }

        if(!jsonArray.contains(name)) {
            jsonArray.add(name);
        }
        annotations.put(annotation, jsonArray.toString());
    }

    private void delBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.warning("Can't get devops client.");
            return;
        }

        PipelineConfig pc = client.pipelineConfigs().inNamespace(namespace).withName(name).get();
        if(pc == null) {
            logger.warning(String.format("Can't find PipelineConfig by namespace: %s, name: %s.", namespace, name));
            return;
        }

        delBranchAnnotation(pc, branchName);
        updatePipelineConfig(client, namespace, name, pc);
    }

    private void delStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.warning("Can't get devops client.");
            return;
        }

        PipelineConfig pc = client.pipelineConfigs().inNamespace(namespace).withName(name).get();
        if(pc == null) {
            logger.warning(String.format("Can't find PipelineConfig by namespace: %s, name: %s.", namespace, name));
            return;
        }

        delStaleBranchAnnotation(pc, branchName);
        updatePipelineConfig(client, namespace, name, pc);
    }

    private void delBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        delBranchAnnotation(pc, MULTI_BRANCH_BRANCH, name);
    }

    private void delStaleBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        delBranchAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, name);
    }

    private void delBranchAnnotation(@NotNull PipelineConfig pc, final String annotation, String name) {
        ObjectMeta meta = pc.getMetadata();
        Map<String, String> annotations = meta.getAnnotations();
        if(annotations == null) {
            return;
        }

        String branchJson = annotations.get(annotation);
        if(branchJson == null) {
            return;
        }

        try {
            JSONArray jsonArray = JSONArray.fromObject(branchJson);
            jsonArray.remove(name);

            annotations.put(annotation, jsonArray.toString());
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    private void updatePipelineConfig(AlaudaDevOpsClient client, String namespace, String name, PipelineConfig pc) {
        client.pipelineConfigs().inNamespace(namespace)
                .withName(name).edit().editMetadata()
                .addToAnnotations(pc.getMetadata().getAnnotations())
                .endMetadata().done();
    }
}
