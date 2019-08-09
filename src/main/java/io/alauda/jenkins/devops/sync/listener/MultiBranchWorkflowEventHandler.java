package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.util.PipelineGenerator;
import io.kubernetes.client.models.V1ObjectMeta;
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

            if(PipelineGenerator.isPR(item)) {
                // we consider it as a pr
                addPRAnnotation(parent, name);
                logger.info(String.format("add a pr %s", name));
            } else {
                addBranchAnnotation(parent, name);
                logger.info(String.format("add a branch %s", name));
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
                if(PipelineGenerator.isPR(item)) {
                    delPRAnnotation(parent, name);
                    addStalePRAnnotation(parent, name);
                    logger.info(String.format("disable a pr %s", name));
                } else {
                    delBranchAnnotation(parent, name);
                    addStaleBranchAnnotation(parent, name);
                    logger.info(String.format("disable a branch %s", name));
                }
            } else {
                // when the deleted branch had been restored

                if(PipelineGenerator.isPR(item)) {
                    delStalePRAnnotation(parent, name);
                    addPRAnnotation(parent, name);
                    logger.info(String.format("enable a pr %s", name));
                } else {
                    delStaleBranchAnnotation(parent, name);
                    addBranchAnnotation(parent, name);
                    logger.info(String.format("enable a branch %s", name));
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

            if(PipelineGenerator.isPR(item)) {
                delStalePRAnnotation(parent, name);
                logger.info(String.format("del a stale pr %s", name));
            } else {
                delStaleBranchAnnotation(parent, name);
                logger.info(String.format("del a stale branch %s", name));
            }
        }
    }

    private void addStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();


        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        if(pc == null) {
            logger.warning(String.format("Can't find PipelineConfig by namespace: %s, name: %s.", namespace, name));
            return;
        }

        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);
        addStaleBranchAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        addStalePRAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        addBranchAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addPRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();


        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        addPRAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addBranchAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        addAnnotation(pc, MULTI_BRANCH_BRANCH, name);
    }

    private void addPRAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        addAnnotation(pc, MULTI_BRANCH_PR, name);
    }

    private void addStaleBranchAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        addAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, name);
    }

    private void addStalePRAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        addAnnotation(pc, MULTI_BRANCH_STALE_PR, name);
    }

    private void addAnnotation(@NotNull V1alpha1PipelineConfig pc, final String annotation, String name) {
        V1ObjectMeta meta = pc.getMetadata();
        Map<String, String> annotations = meta.getAnnotations();
        if(annotations == null) {
            annotations = new HashMap<>();
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

        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delPRAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delBranchAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();


        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delStalePRAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        V1alpha1PipelineConfig pc = Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delStaleBranchAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delBranchAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_BRANCH, name);
    }

    private void delPRAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_PR, name);
    }

    private void delStaleBranchAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, name);
    }

    private void delStalePRAnnotation(@NotNull V1alpha1PipelineConfig pc, String name) {
        delAnnotation(pc, MULTI_BRANCH_STALE_PR, name);
    }

    private void delAnnotation(@NotNull V1alpha1PipelineConfig pc, final String annotation, String name) {
        V1ObjectMeta meta = pc.getMetadata();
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

}
