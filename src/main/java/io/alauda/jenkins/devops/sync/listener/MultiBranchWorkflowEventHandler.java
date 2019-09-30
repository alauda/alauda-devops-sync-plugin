package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.multiBranch.PullRequest;
import io.alauda.jenkins.devops.sync.util.PipelineGenerator;
import io.kubernetes.client.models.V1ObjectMeta;
import jenkins.scm.api.metadata.ObjectMetadataAction;
import net.sf.json.JSONArray;
import net.sf.json.JSONException;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;

import javax.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
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

        String scmURL = "";
        ObjectMetadataAction metadataAction = item.getAction(ObjectMetadataAction.class);
        if(metadataAction != null) {
            scmURL = metadataAction.getObjectUrl();
        }

        if(pro != null) {
            final String branchName = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            PullRequest pr = PipelineGenerator.getPR(item);
            if(pr != null) {
                // we consider it as a pr
                pr.setUrl(scmURL);
                addPRAnnotation(parent, pr, branchName);
                logger.info(String.format("add a pr %s", branchName));
            } else {
                addBranchAnnotation(parent, branchName, scmURL);
                logger.info(String.format("add a branch %s", branchName));
            }
        }
    }

    @Override
    public void onUpdated(WorkflowJob item) {
        BranchJobProperty pro = item.getProperty(BranchJobProperty.class);

        String scmURL = "";
        ObjectMetadataAction metadataAction = item.getAction(ObjectMetadataAction.class);
        if(metadataAction != null) {
            scmURL = metadataAction.getObjectUrl();
        }
        if(pro != null) {
            final String branchName = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            if(item.isDisabled()) {
                // it's a stale pipeline for multi-branch
                PullRequest pr = PipelineGenerator.getPR(item);
                if(pr != null) {
                    delPRAnnotation(parent, branchName);
                    addStalePRAnnotation(parent, branchName);
                    logger.info(String.format("disable a pr %s", branchName));
                } else {
                    delBranchAnnotation(parent, branchName);
                    addStaleBranchAnnotation(parent, branchName);
                    logger.info(String.format("disable a branch %s", branchName));
                }
            } else {
                // when the deleted branch had been restored

                PullRequest pr = PipelineGenerator.getPR(item);
                if(pr != null) {
                    pr.setUrl(scmURL);
                    delStalePRAnnotation(parent, branchName);
                    addPRAnnotation(parent, pr, branchName);
                    logger.info(String.format("enable a pr %s", branchName));
                } else {
                    delStaleBranchAnnotation(parent, branchName);
                    addBranchAnnotation(parent, branchName, scmURL);
                    logger.info(String.format("enable a branch %s", branchName));
                }
            }
        }
    }

    @Override
    public void onDeleted(WorkflowJob item) {
        BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
        if(pro != null) {
            String branchName = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            PullRequest pr = PipelineGenerator.getPR(item);
            if(pr != null) {
                delStalePRAnnotation(parent, branchName);
                logger.info(String.format("del a stale pr %s", branchName));
            } else {
                delStaleBranchAnnotation(parent, branchName);
                logger.info(String.format("del a stale branch %s", branchName));
            }
        }
    }

    private void addStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if(pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);
            addStaleBranchAnnotation(newPc, branchName);
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private void addStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String prName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if (pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            addAnnotation(newPc, MULTI_BRANCH_STALE_PR, prName);
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private void addBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName, String scmURL) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if(pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            addAnnotation(newPc, MULTI_BRANCH_BRANCH, branchName);
            setAnnotation(newPc, "alauda.io/jenkins." + annotationKeySpec(branchName) + ".url", scmURL);
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private String annotationKeySpec(String key) {
        if (key == null) {
            return null;
        }

        return key.replaceAll("/", "-");
    }

    private void addPRAnnotation(@NotNull WorkflowMultiBranchProject job, PullRequest pr, String prName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if(pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            addAnnotation(newPc, MULTI_BRANCH_PR, prName);
            setAnnotation(newPc, "alauda.io/jenkins." + prName, pr);
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private void addStaleBranchAnnotation(@NotNull V1alpha1PipelineConfig pc, String branchName) {
        addAnnotation(pc, MULTI_BRANCH_STALE_BRANCH, branchName);
    }

    private void setAnnotation(@NotNull V1alpha1PipelineConfig pc, final String annotation, Object obj) {
        V1ObjectMeta meta = pc.getMetadata();
        Map<String, String> annotations = meta.getAnnotations();
        if(annotations == null) {
            annotations = new HashMap<>();
            meta.setAnnotations(annotations);
        }

        annotations.put(annotation, obj instanceof String ? obj.toString() : JSONObject.fromObject(obj).toString());
    }

    private void delAnnotation(@NotNull V1alpha1PipelineConfig pc, final String annotation) {
        V1ObjectMeta meta = pc.getMetadata();
        Map<String, String> annotations = meta.getAnnotations();
        if(annotations == null) {
            annotations = new HashMap<>();
            meta.setAnnotations(annotations);
        }
        annotations.remove(annotation);
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

        logger.log(Level.FINE, String.format("Update annotation, before is: %s, after is: %s", branchJson, jsonArray.toString()));
    }

    private void delPRAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if (pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            delAnnotation(newPc, MULTI_BRANCH_PR, branchName);
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private void delBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if (pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            delAnnotation(newPc, MULTI_BRANCH_BRANCH, branchName);
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private void delStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String prName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if (pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            delAnnotation(newPc, MULTI_BRANCH_STALE_PR, prName);
            delAnnotation(newPc, "alauda.io/jenkins." + prName);
            delAnnotation(newPc, "alauda.io/jenkins." + annotationKeySpec(prName) + ".url");
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
    }

    private void delStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        synchronized (MultiBranchWorkflowEventHandler.class) {
            V1alpha1PipelineConfig pc = getPipelineConfig(job);
            if (pc == null) {
                return;
            }
            V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

            delAnnotation(newPc, MULTI_BRANCH_STALE_BRANCH, branchName);
            delAnnotation(newPc, "alauda.io/jenkins." + annotationKeySpec(branchName) + ".url");
            Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
        }
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

    private V1alpha1PipelineConfig getPipelineConfig(WorkflowMultiBranchProject job) {
        AlaudaJobProperty pro = job.getProperties().get(MultiBranchProperty.class);
        if(pro == null) {
            logger.warning(String.format("No AlaudaJobProperty in job %s.", job.getFullName()));
            return null;
        }

        String namespace = pro.getNamespace();
        String name = pro.getName();

        return Clients.get(V1alpha1PipelineConfig.class).lister().namespace(namespace).get(name);
    }
}
