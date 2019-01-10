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
import jenkins.scm.api.metadata.ContributorMetadataAction;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Annotations.*;

@Extension
@Restricted(DoNotUse.class)
public class MultiBranchWorkflowEventHandler implements ItemEventHandler<WorkflowJob> {
    private static final Logger logger = Logger.getLogger(MultiBranchWorkflowEventHandler.class.getName());

    @Override
    public boolean accept(Item item) {
        if(item == null) {
            return false;
        }

        ItemGroup<? extends Item> parent = item.getParent();
        return (parent instanceof WorkflowMultiBranchProject);
    }

    @Override
    public void onCreated(WorkflowJob item) {
        BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            if(isPR(item)) {
                // we consider it as a pr
                addPRAnnotation(parent, name);
            } else {
                addBranchAnnotation(parent, name);
            }
        }
    }

    @Override
    public void onUpdated(WorkflowJob item) {
        BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            if(item.isDisabled()) {
                // it's a stale pipeline for multi-branch
                if(isPR(item)) {
                    delPRAnnotation(parent, name);
                    addStalePRAnnotation(parent, name);
                } else {
                    delBranchAnnotation(parent, name);
                    addStaleBranchAnnotation(parent, name);
                }
            } else {
                // when the deleted branch had been restored

                if(isPR(item)) {
                    delStalePRAnnotation(parent, name);
                    addPRAnnotation(parent, name);
                } else {
                    delStaleBranchAnnotation(parent, name);
                    addBranchAnnotation(parent, name);
                }
            }
        }
    }

    @Override
    public void onDeleted(WorkflowJob item) {
        BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getEncodedName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            if(isPR(item)) {
                delStalePRAnnotation(parent, name);
            } else {
                delStaleBranchAnnotation(parent, name);
            }
        }
    }

    private boolean isPR(WorkflowJob item) {
        return item.getAction(ContributorMetadataAction.class) != null;
    }

    private void addStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
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

    private void addStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
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

        addStalePRAnnotation(pc, branchName);
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

    private void addPRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
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

        addPRAnnotation(pc, branchName);
        updatePipelineConfig(client, namespace, name, pc);
    }

    private void addBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        addBranchAnnotation(pc, MULTI_BRANCH_BRANCH, name);
    }

    private void addPRAnnotation(@NotNull PipelineConfig pc, String name) {
        addBranchAnnotation(pc, MULTI_BRANCH_PR, name);
    }

    private void addStaleBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        addBranchAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, name);
    }

    private void addStalePRAnnotation(@NotNull PipelineConfig pc, String name) {
        addBranchAnnotation(pc, MULTI_BRANCH_STALE_PR, name);
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

    private void delPRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
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

        delPRAnnotation(pc, branchName);
        updatePipelineConfig(client, namespace, name, pc);
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

    private void delStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
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

        delStalePRAnnotation(pc, branchName);
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
        delAnnotation(pc, MULTI_BRANCH_BRANCH, name);
    }

    private void delPRAnnotation(@NotNull PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_PR, name);
    }

    private void delStaleBranchAnnotation(@NotNull PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, name);
    }

    private void delStalePRAnnotation(@NotNull PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_STALE_PR, name);
    }

    private void delAnnotation(@NotNull PipelineConfig pc, final String annotation, String name) {
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
