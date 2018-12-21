package io.alauda.jenkins.devops.sync;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy;
import hudson.Extension;
import hudson.model.ItemGroup;
import hudson.util.XStream2;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.folder.CronFolderTrigger;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.alauda.jenkins.devops.sync.util.PipelineConfigToJobMap;
import io.alauda.kubernetes.api.model.CodeRepository;
import io.alauda.kubernetes.api.model.CodeRepositoryRef;
import io.alauda.kubernetes.api.model.CodeRepositorySpec;
import io.alauda.kubernetes.api.model.MultiBranchBehaviours;
import io.alauda.kubernetes.api.model.MultiBranchOrphan;
import io.alauda.kubernetes.api.model.MultiBranchPipeline;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.OriginCodeRepository;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigSpec;
import io.alauda.kubernetes.api.model.PipelineSource;
import io.alauda.kubernetes.api.model.PipelineSourceGit;
import io.alauda.kubernetes.api.model.PipelineStrategyJenkins;
import io.alauda.kubernetes.api.model.PipelineTrigger;
import io.alauda.kubernetes.api.model.PipelineTriggerCron;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;

import javax.validation.constraints.NotNull;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

/**
 * TODO 需要考虑的兼容问题： 当 git 服务从不支持 PR 转到支持的情况下，如何保持不修改已经有结构（例如：gitlab 暂时不支持）
 * 对于不支持 pr 的情况，fallback 到普通的 git
 */
@Extension
public class ConvertToMultiBranch implements PipelineConfigConvert<WorkflowMultiBranchProject> {
    private final Logger logger = Logger.getLogger(ConvertToMultiBranch.class.getName());

    @Override
    public boolean accept(PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            return false;
        }

        Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
        return (PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
    }

    @Override
    public WorkflowMultiBranchProject convert(PipelineConfig pipelineConfig) throws IOException {
        String jobName = AlaudaUtils.jenkinsJobName(pipelineConfig);
        String jobFullName = AlaudaUtils.jenkinsJobFullName(pipelineConfig);
        String namespace = pipelineConfig.getMetadata().getNamespace();
        String name = pipelineConfig.getMetadata().getName();
        String uid = pipelineConfig.getMetadata().getUid();
        String resourceVer = pipelineConfig.getMetadata().getResourceVersion();

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            return null;
        }

        WorkflowMultiBranchProject job = PipelineConfigToJobMap.getMultiBranchByPC(pipelineConfig);
        Jenkins activeInstance = Jenkins.getInstance();
        ItemGroup parent = activeInstance;
        if (job == null) {
            logger.info(String.format("No job [namespace: %s, name: %s] found from the cache.", namespace, name));
            job = (WorkflowMultiBranchProject) activeInstance.getItemByFullName(jobFullName);
        }

        boolean newJob = job == null;
        if (newJob) {
            parent = AlaudaUtils.getFullNameParent(activeInstance, jobFullName, AlaudaUtils.getNamespace(pipelineConfig));
            job = new WorkflowMultiBranchProject(parent, jobName);
            job.addProperty(new MultiBranchProperty(namespace, name, uid, resourceVer));

            logger.info(String.format("New MultiBranchProject [%s] will be created.", job.getFullName()));
        } else {
            MultiBranchProperty mbProperty = job.getProperties().get(MultiBranchProperty.class);
            if(mbProperty == null) {
                logger.warning(String.format("No MultiBranchProperty in job: %s.", job.getFullName()));
                return null;
            }

            if(isSameJob(pipelineConfig, mbProperty)) {
                mbProperty.setResourceVersion(resourceVer);

                PipelineConfigToJobMap.putJobWithPipelineConfig(job, pipelineConfig);
            } else {
                return null;
            }
        }

        // we just support only one source
        job.getSourcesList().clear();

        PipelineConfigSpec spec = pipelineConfig.getSpec();
        PipelineStrategyJenkins strategy = spec.getStrategy().getJenkins();
        if(strategy == null) {
            logger.severe(String.format("No strategy in here, namespace: %s, name: %s.", namespace, name));
            return null;
        }

        WorkflowBranchProjectFactory wfFactory = new WorkflowBranchProjectFactory();
        wfFactory.setScriptPath(strategy.getJenkinsfilePath());
        job.setProjectFactory(wfFactory);

        MultiBranchBehaviours behaviours = null;
        // orphaned setting
        MultiBranchPipeline multiBranch = strategy.getMultiBranch();
        if(multiBranch != null) {
            MultiBranchOrphan orphaned = multiBranch.getOrphaned();
            DefaultOrphanedItemStrategy orphanedStrategy;
            if(orphaned != null) {
                orphanedStrategy = new DefaultOrphanedItemStrategy(
                        true, String.valueOf(orphaned.getDays()), String.valueOf(orphaned.getMax()));
            } else {
                orphanedStrategy = new DefaultOrphanedItemStrategy(false, "", "");
            }
            job.setOrphanedItemStrategy(orphanedStrategy);

            behaviours = multiBranch.getBehaviours();
        }

        PipelineSource source = spec.getSource();
        SCMSource scmSource = null;

        CodeRepositoryRef codeRepoRef = source.getCodeRepository();
        PipelineSourceGit gitSource = source.getGit();
        GitProviderMultiBranch gitProvider = null;
        // TODO maybe put some redundancy into annotation
        if(codeRepoRef != null) {
            // cases for git provider
            String codeRepoName = codeRepoRef.getName();

            CodeRepository codeRep = client.codeRepositories().inNamespace(namespace).withName(codeRepoName).get();
            if(codeRep != null) {
                CodeRepositorySpec codeRepoSpec = codeRep.getSpec();
                OriginCodeRepository codeRepo = codeRepoSpec.getRepository();
                String repoOwner = codeRepo.getOwner().getName();
                String repository = codeRepo.getName();
                String codeRepoType = codeRepo.getCodeRepoServiceType();

                Optional<GitProviderMultiBranch> gitProviderOpt = Jenkins.getInstance()
                        .getExtensionList(GitProviderMultiBranch.class)
                        .stream().filter(git -> git.accept(codeRepo.getCodeRepoServiceType()))
                        .findFirst();
                boolean supported = gitProviderOpt.isPresent();
                if(supported) {
                    // TODO need to deal with the private git providers
                    gitProvider = gitProviderOpt.get();
                    scmSource = gitProvider.getSCMSource(repoOwner, repository);
                    if(scmSource == null) {
                        logger.warning(String.format("Can't create instance for AbstractGitSCMSource. Type is %s.", codeRepoType));
                        return null;
                    }
                } else {
                    // TODO should take care of clean up job
                    logger.warning(String.format("Not support for %s, codeRepo name is %s. Fall back to general git.", codeRepoType, codeRepoName));

                    scmSource = new GitSCMSource(codeRepo.getCloneURL());
                }
            } else {
                logger.warning(String.format("Can't found codeRepository %s, namespace %s.", codeRepoName, namespace));
            }
        } else if(gitSource != null) {
            // general git
            scmSource = new GitSCMSource(gitSource.getUri());
        } else {
            logger.warning("Not found git repository.");
        }

        // handle common settings
        if(scmSource != null) {
            handleSCMTraits(scmSource, behaviours, gitProvider);

            handleCredentials(scmSource, pipelineConfig);

            job.getSourcesList().add(new BranchSource(scmSource));
        }

        job.getTriggers().clear();
        List<PipelineTrigger> triggers = spec.getTriggers();
        if(triggers != null) {
            Optional<PipelineTrigger> triggerOpt = triggers.stream().filter(
                    trigger -> PIPELINE_TRIGGER_TYPE_CRON.equals(trigger.getType())).findFirst();
            if(triggerOpt.isPresent()) {
                PipelineTrigger trigger = triggerOpt.get();
                PipelineTriggerCron cron = trigger.getCron();

                try {
                    job.addTrigger(new CronFolderTrigger(cron.getRule()));
                } catch (ANTLRException e) {
                    e.printStackTrace();
                }
            }
        }

        // going to save the configuration
        InputStream jobStream = new StringInputStream(new XStream2().toXML(job));
        if (newJob) {
            if (parent instanceof Folder) {
                Folder folder = (Folder) parent;
                folder.createProjectFromXML(jobName, jobStream).save();
            } else {
                activeInstance.createProjectFromXML(jobName, jobStream).save();
            }

            PipelineConfigToJobMap.putJobWithPipelineConfig(job, pipelineConfig);

            logger.info("Created job " + jobName + " from PipelineConfig " + NamespaceName.create(pipelineConfig)
                    + " with revision: " + resourceVer);
        } else {
            updateJob(job, jobStream, jobName, pipelineConfig);
        }

        Map<String, String> logURLs = Collections.singletonMap(ALAUDA_DEVOPS_ANNOTATIONS_MULTI_BRANCH_SCAN_LOG,
                String.format("/job/%s/job/%s/indexing/logText/progressiveText", namespace, jobName));
        addAnnotations(pipelineConfig, logURLs, client);

        updatePipelineConfigPhase(pipelineConfig);

        return job;
    }

    // TODO should create a PR to unit the interface
    private void handleSCMTraits(@NotNull SCMSource source, MultiBranchBehaviours behaviours, GitProviderMultiBranch gitProvider) {
        List<SCMSourceTrait> traits = new ArrayList<>();
        if(behaviours != null && StringUtils.isNotBlank(behaviours.getFilterExpression())) {
            traits.add(new RegexSCMHeadFilterTrait(behaviours.getFilterExpression()));
        }

        if(gitProvider != null) {
            traits.add(gitProvider.getBranchDiscoverTrait(1));
            traits.add(gitProvider.getOriginPRTrait(1));
            traits.add(gitProvider.getForkPRTrait(1));
        } else {
            traits.add(new BranchDiscoveryTrait());
        }

        try {
            Method method = source.getClass().getMethod("setTraits", List.class);
            method.invoke(source, traits);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            logger.severe(String.format("Can't setting traits, source class is %s", source.getClass()));
        }
    }

    // TODO should create a PR to unit the interface
    private void handleCredentials(@NotNull SCMSource source, @NotNull PipelineConfig pipelineConfig) throws IOException {
        String credentialId = CredentialsUtils.updateSourceCredentials(pipelineConfig);

        try {
            Method method = source.getClass().getMethod("setCredentialsId", String.class);
            method.invoke(source, credentialId);
        } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
            logger.severe(String.format("Can't setting credentials, source class is %s", source.getClass()));
        }
    }

    private void addAnnotations(@NotNull PipelineConfig pc, @NotNull Map<String, String> annotations,
                                @NotNull AlaudaDevOpsClient client) {
        ObjectMeta meta = pc.getMetadata();
        client.pipelineConfigs().inNamespace(meta.getNamespace())
                .withName(meta.getName()).edit()
                .editMetadata().addToAnnotations(annotations)
                .endMetadata().done();
    }

}
