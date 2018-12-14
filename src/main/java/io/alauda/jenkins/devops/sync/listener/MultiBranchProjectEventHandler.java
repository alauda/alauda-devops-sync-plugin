package io.alauda.jenkins.devops.sync.listener;

import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy;
import com.cloudbees.hudson.plugins.folder.computed.OrphanedItemStrategy;
import hudson.Extension;
import hudson.model.Item;
import hudson.util.PersistedList;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.dsl.PipelineConfigResource;
import io.alauda.jenkins.devops.sync.AlaudaJobProperty;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.kubernetes.api.model.DoneablePipelineConfig;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigFluent;
import io.alauda.kubernetes.api.model.PipelineConfigSpecFluent;
import io.alauda.kubernetes.api.model.PipelineStrategyFluent;
import io.alauda.kubernetes.api.model.PipelineStrategyJenkinsFluent;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

@Extension
public class MultiBranchProjectEventHandler implements ItemEventHandler<WorkflowMultiBranchProject> {
    private static final Logger logger = Logger.getLogger(MultiBranchProjectEventHandler.class.getName());

    @Override
    public boolean accept(Item item) {
        return (item instanceof WorkflowMultiBranchProject);
    }

    @Override
    public void onCreated(WorkflowMultiBranchProject item) {
    }

    @Override
    public void onUpdated(WorkflowMultiBranchProject item) {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.severe("Can't get AlaudaDevOpsClient when receive WorkflowMultiBranchProject update event.");
            return;
        }

        AlaudaJobProperty property = item.getProperties().get(MultiBranchProperty.class);
        if(property == null) {
            logger.warning(String.format("Can't find MultiBranchProperty from %s, skip update event.", item.getFullName()));
            return;
        }

        String ns = property.getNamespace();
        String name = property.getName();
        NamespaceName nsName = new NamespaceName(ns, name);

        PipelineConfigResource<PipelineConfig, DoneablePipelineConfig, Void, Pipeline> pc =
                client.pipelineConfigs().inNamespace(ns).withName(name);
        if(pc == null) {
            logger.warning(String.format("Can't find pipelineconfig %s.", nsName.toString()));
            return;
        }

        logger.info(String.format("Going to update pipelineconfig %s.", nsName.toString()));

        PipelineStrategyFluent.JenkinsNested<PipelineConfigSpecFluent.StrategyNested<PipelineConfigFluent.SpecNested<DoneablePipelineConfig>>>
                editJenkins = pc.edit().editSpec().editStrategy().editJenkins();

        // setting project factory
        BranchProjectFactory<WorkflowJob, WorkflowRun> factory = item.getProjectFactory();
        if(factory instanceof WorkflowBranchProjectFactory) {
            String scriptPath = ((WorkflowBranchProjectFactory) factory).getScriptPath();
            editJenkins = editJenkins.withJenkinsfilePath(scriptPath);
        }

        PipelineStrategyJenkinsFluent.MultiBranchNested<PipelineStrategyFluent.JenkinsNested<PipelineConfigSpecFluent.StrategyNested<PipelineConfigFluent.SpecNested<DoneablePipelineConfig>>>>
                edit = editJenkins.editMultiBranch();

        PersistedList<BranchSource> sourcesList = item.getSourcesList();
        // only support one branch source for now
        BranchSource branchSource = sourcesList.isEmpty() ? null : sourcesList.get(0);
        if(branchSource != null && branchSource.getSource() != null) {
            SCMSource scmSource = branchSource.getSource();

            try {
                Method getTraits = scmSource.getClass().getMethod("getTraits");
                List<SCMSourceTrait> traits = (List<SCMSourceTrait>) getTraits.invoke(scmSource);
                if(traits != null) {
                    for(SCMSourceTrait trait : traits) {
                        if(trait instanceof RegexSCMHeadFilterTrait) {
                            String regex = ((RegexSCMHeadFilterTrait) trait).getRegex();

                            edit = edit.editBehaviours().withFilterExpression(regex).endBehaviours();
                        }
                    }
                }
            } catch (NoSuchMethodException | IllegalAccessException
                    | InvocationTargetException e) {
                e.printStackTrace();
            }
        }

        // setting orphaned item strategy
        OrphanedItemStrategy orphanedItemStrategy = item.getOrphanedItemStrategy();
        if(orphanedItemStrategy instanceof DefaultOrphanedItemStrategy) {
            DefaultOrphanedItemStrategy defStrategy = ((DefaultOrphanedItemStrategy) orphanedItemStrategy);
            if(defStrategy.isPruneDeadBranches()) {
                int days = defStrategy.getDaysToKeep();
                int max = defStrategy.getNumToKeep();

                edit = edit.editOrphaned().withDays(days).withMax(max).endOrphaned();
            } else {
                edit = edit.editOrNewOrphanedLike(null).endOrphaned();
            }
        } else {
            edit = edit.editOrNewOrphanedLike(null).endOrphaned();
        }

        edit.endMultiBranch().endJenkins().endStrategy().endSpec().done();
        logger.info(String.format("Done with update pipelineconfig %s.", nsName.toString()));
    }

    @Override
    public void onDeleted(WorkflowMultiBranchProject item) {
        AlaudaJobProperty property = item.getProperties().get(MultiBranchProperty.class);
        if(property == null) {
            logger.warning(String.format("Can't find MultiBranchProperty from %s, skip delete event.", item.getFullName()));
            return;
        }

        String ns = property.getNamespace();
        String name = property.getName();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.severe("Can't get AlaudaDevOpsClient when receive WorkflowMultiBranchProject delete event.");
            return;
        }

        PipelineConfig pc = client.pipelineConfigs().inNamespace(ns).withName(name).get();
        if(pc != null) {
            Boolean result = client.pipelineConfigs().inNamespace(ns).withName(name).delete();
            logger.info(String.format("PipelineConfig [%s]-[%s] delete result [%s].", ns, name, result.toString()));
        }

        PipelineConfigToJobMap.removeJobWithPipelineConfig(pc);
    }
}
