package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineParameter;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
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
import java.util.List;
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

        String scmURL = "";
        ObjectMetadataAction metadataAction = item.getAction(ObjectMetadataAction.class);
        if(metadataAction != null) {
            scmURL = metadataAction.getObjectUrl();
        }

        if(pro != null) {
            String name = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();


            PipelineConfigUpdater pipelineConfigUpdater = new PipelineConfigUpdater(item, name);
            PullRequest pr = PipelineGenerator.getPR(item);
            if(pr != null) {
                // we consider it as a pr
                pr.setUrl(scmURL);
//                addPRAnnotation(parent, pr, name);
                pipelineConfigUpdater.addPRAnnotation(pr);
                logger.info(String.format("add a pr %s", name));
            } else {
//                addBranchAnnotation(parent, name, scmURL);
                pipelineConfigUpdater.addBranchAnnotation(scmURL);
                logger.info(String.format("add a branch %s", name));
            }

            pipelineConfigUpdater.addParameters();
            pipelineConfigUpdater.commit();
        }
    }

    private class PipelineConfigUpdater {
        private WorkflowJob job;
        private String branchName;

        private V1alpha1PipelineConfig oldPC;
        private V1alpha1PipelineConfig newPC;

        PipelineConfigUpdater(WorkflowJob job, String branchName) {
            this.job = job;
            this.branchName = branchName;

            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) job.getParent();
            oldPC = getPipelineConfig(parent);
            if(oldPC == null) {
                return;
            }
            newPC = DeepCopyUtils.deepCopy(oldPC);
        }

        void addPRAnnotation(PullRequest pr) {
            addAnnotation(MULTI_BRANCH_PR, branchName);
            setAnnotation("alauda.io/jenkins." + branchName, pr);
        }

        void addBranchAnnotation(String scmURL) {
            addAnnotation(MULTI_BRANCH_BRANCH, branchName);
            setAnnotation("alauda.io/jenkins." + branchName + ".url", scmURL);
        }

        void delPRAnnotation() {
            delAnnotation(MULTI_BRANCH_PR, branchName);
        }

        void delStalePRAnnotation() {
            delAnnotation(MULTI_BRANCH_STALE_PR, branchName);
            delAnnotation("alauda.io/jenkins." + branchName);
            delAnnotation("alauda.io/jenkins." + branchName + ".url");
        }

        void addPRAnnotation(PullRequest pr, String prName) {
            addAnnotation(MULTI_BRANCH_PR, prName);
            setAnnotation("alauda.io/jenkins." + prName, pr);
        }

        void delStaleBranchAnnotation() {
            delAnnotation(MULTI_BRANCH_STALE_BRANCH, branchName);
            delAnnotation("alauda.io/jenkins." + branchName + ".url");
        }

        void delAnnotation(String annotation, String name) {
            V1ObjectMeta meta = newPC.getMetadata();
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

        void delAnnotation(String annotation) {
            V1ObjectMeta meta = newPC.getMetadata();
            Map<String, String> annotations = meta.getAnnotations();
            if(annotations == null) {
                annotations = new HashMap<>();
                meta.setAnnotations(annotations);
            }
            annotations.remove(annotation);
        }

        void delBranchAnnotation() {
            delAnnotation(MULTI_BRANCH_BRANCH, branchName);
        }

        void addStalePRAnnotation() {
            addAnnotation(MULTI_BRANCH_STALE_PR, branchName);
        }

        void setAnnotation(final String annotation, Object obj) {
            V1ObjectMeta meta = newPC.getMetadata();
            Map<String, String> annotations = meta.getAnnotations();
            if(annotations == null) {
                annotations = new HashMap<>();
                meta.setAnnotations(annotations);
            }

            String jsonStr = null;
            if(obj instanceof String) {
                jsonStr = obj.toString();
            } else if(obj instanceof List){
                jsonStr = JSONArray.fromObject(obj).toString();
            } else {
                jsonStr = JSONObject.fromObject(obj).toString();
            }

            annotations.put(annotation, jsonStr);
        }

        void addAnnotation(final String annotation, String name) {
            V1ObjectMeta meta = newPC.getMetadata();
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

        void addParameters() {
            List<V1alpha1PipelineParameter> pipelineParameters =
                    PipelineConfigToJobMapper.getPipelineParameter(job);
            setAnnotation("alauda.io/jenkins." + branchName + ".params", pipelineParameters);
        }

        void delParameters() {
            delAnnotation("alauda.io/jenkins." + branchName + ".params");
        }

        void commit() {
            Clients.get(V1alpha1PipelineConfig.class).update(oldPC, newPC);
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
            String name = pro.getBranch().getName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            PipelineConfigUpdater pipelineConfigUpdater = new PipelineConfigUpdater(item, name);
            if(item.isDisabled()) {
                // it's a stale pipeline for multi-branch
                PullRequest pr = PipelineGenerator.getPR(item);
                if(pr != null) {
//                    delPRAnnotation(parent, name);
                    pipelineConfigUpdater.delPRAnnotation();
//                    addStalePRAnnotation(parent, name);
                    pipelineConfigUpdater.addStalePRAnnotation();
                    logger.info(String.format("disable a pr %s", name));
                } else {
//                    delBranchAnnotation(parent, name);
                    pipelineConfigUpdater.delBranchAnnotation();
//                    addStaleBranchAnnotation(parent, name);
                    pipelineConfigUpdater.addAnnotation(MULTI_BRANCH_STALE_BRANCH, name);
                    logger.info(String.format("disable a branch %s", name));
                }
            } else {
                // when the deleted branch had been restored

                PullRequest pr = PipelineGenerator.getPR(item);
                if(pr != null) {
                    pr.setUrl(scmURL);
//                    delStalePRAnnotation(parent, name);
                    pipelineConfigUpdater.delStalePRAnnotation();
//                    addPRAnnotation(parent, pr, name);
                    pipelineConfigUpdater.addPRAnnotation(pr, name);
                    logger.info(String.format("enable a pr %s", name));
                } else {
//                    delStaleBranchAnnotation(parent, name);
                    pipelineConfigUpdater.delStaleBranchAnnotation();
//                    addBranchAnnotation(parent, name, scmURL);
                    pipelineConfigUpdater.addBranchAnnotation(scmURL);
                    logger.info(String.format("enable a branch %s", name));
                }
            }

            pipelineConfigUpdater.commit();
        }
    }

    @Override
    public void onDeleted(WorkflowJob item) {
        BranchJobProperty pro = item.getProperty(BranchJobProperty.class);
        if(pro != null) {
            String name = pro.getBranch().getEncodedName();
            WorkflowMultiBranchProject parent = (WorkflowMultiBranchProject) item.getParent();

            PipelineConfigUpdater pipelineConfigUpdater = new PipelineConfigUpdater(item, name);
            PullRequest pr = PipelineGenerator.getPR(item);
            if(pr != null) {
//                delStalePRAnnotation(parent, name);
                pipelineConfigUpdater.delStalePRAnnotation();
                logger.info(String.format("del a stale pr %s", name));
            } else {
//                delStaleBranchAnnotation(parent, name);
                pipelineConfigUpdater.delStaleBranchAnnotation();
                logger.info(String.format("del a stale branch %s", name));
            }

            pipelineConfigUpdater.delParameters();
            pipelineConfigUpdater.commit();
        }
    }

    private void addStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);
        addStaleBranchAnnotation(newPc, branchName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String prName) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        addAnnotation(newPc, MULTI_BRANCH_STALE_PR, prName);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName, String scmURL) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        addAnnotation(newPc, MULTI_BRANCH_BRANCH, branchName);
        setAnnotation(newPc, "alauda.io/jenkins." + branchName + ".url", scmURL);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void addPRAnnotation(@NotNull WorkflowMultiBranchProject job, PullRequest pr, String prName) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        addAnnotation(newPc, MULTI_BRANCH_PR, prName);
        setAnnotation(newPc, "alauda.io/jenkins." + prName, pr);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
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

    private void  addAnnotation(@NotNull V1alpha1PipelineConfig pc, final String annotation, String name) {
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
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        String name = pc.getMetadata().getName();
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delAnnotation(newPc, MULTI_BRANCH_PR, name);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        String name = pc.getMetadata().getName();
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delAnnotation(newPc, MULTI_BRANCH_BRANCH, name);
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delStalePRAnnotation(@NotNull WorkflowMultiBranchProject job, String prName) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delAnnotation(newPc, MULTI_BRANCH_STALE_PR, prName);
        delAnnotation(newPc, "alauda.io/jenkins." + prName);
        delAnnotation(newPc, "alauda.io/jenkins." + prName + ".url");
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
    }

    private void delStaleBranchAnnotation(@NotNull WorkflowMultiBranchProject job, String branchName) {
        V1alpha1PipelineConfig pc = getPipelineConfig(job);
        if(pc == null) {
            return;
        }
        String name = pc.getMetadata().getName();
        V1alpha1PipelineConfig newPc = DeepCopyUtils.deepCopy(pc);

        delAnnotation(newPc, MULTI_BRANCH_STALE_BRANCH, branchName);
        delAnnotation(newPc, "alauda.io/jenkins." + branchName + ".url");
        Clients.get(V1alpha1PipelineConfig.class).update(pc, newPc);
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
