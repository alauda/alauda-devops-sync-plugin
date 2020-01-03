package io.alauda.jenkins.devops.sync.mapper.converter;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy;
import hudson.Extension;
import hudson.model.Item;
import io.alauda.devops.java.client.models.*;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.PrivateGitProviderMultiBranch;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.folder.CronFolderTrigger;
import io.alauda.jenkins.devops.sync.mapper.PipelineConfigMapper;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.models.V1ObjectMeta;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import javax.validation.constraints.NotNull;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowBranchProjectFactory;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class MultibranchWorkflowJobConverter implements JobConverter<WorkflowMultiBranchProject> {
  private static final Logger logger =
      LoggerFactory.getLogger(MultibranchWorkflowJobConverter.class);

  private JenkinsClient jenkinsClient;
  private PipelineConfigMapper mapper;

  public MultibranchWorkflowJobConverter() {
    mapper = new PipelineConfigMapper();
    jenkinsClient = JenkinsClient.getInstance();
  }

  @Override
  public boolean accept(V1alpha1PipelineConfig pipelineConfig) {
    if (pipelineConfig == null) {
      return false;
    }

    Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
    return (PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
  }

  @Override
  public WorkflowMultiBranchProject convert(V1alpha1PipelineConfig pipelineConfig)
      throws PipelineConfigConvertException, IOException {
    String namespace = pipelineConfig.getMetadata().getNamespace();
    String name = pipelineConfig.getMetadata().getName();
    NamespaceName namespaceName = new NamespaceName(namespace, name);

    Item item = jenkinsClient.getItem(namespaceName);
    WorkflowMultiBranchProject job;
    if (item == null) {
      logger.debug("Unable to found a Jenkins job for PipelineConfig '{}/{}'", namespace, name);
      Folder parentFolder = jenkinsClient.upsertFolder(namespace);
      job = new WorkflowMultiBranchProject(parentFolder, mapper.jenkinsJobName(namespace, name));

      V1ObjectMeta meta = pipelineConfig.getMetadata();
      MultiBranchProperty property =
          new MultiBranchProperty(namespace, name, meta.getUid(), meta.getResourceVersion());
      property.setContextAnnotation(property.generateAnnotationAsJSON(pipelineConfig));

      job.addProperty(property);
    } else {
      if (!(item instanceof WorkflowMultiBranchProject)) {
        throw new PipelineConfigConvertException(
            String.format(
                "Unable to update Jenkins job, except a WorkflowMultiBranchProject but found a %s",
                item.getClass()));
      }
      job = ((WorkflowMultiBranchProject) item);

      MultiBranchProperty mbProperty = job.getProperties().get(MultiBranchProperty.class);
      mbProperty.setContextAnnotation(mbProperty.generateAnnotationAsJSON(pipelineConfig));
      mbProperty.setResourceVersion(pipelineConfig.getMetadata().getResourceVersion());
    }

    job.getSourcesList().clear();

    V1alpha1PipelineStrategyJenkins strategy = pipelineConfig.getSpec().getStrategy().getJenkins();

    WorkflowBranchProjectFactory wfFactory = new WorkflowBranchProjectFactory();
    wfFactory.setScriptPath(strategy.getJenkinsfilePath());
    job.setProjectFactory(wfFactory);

    V1alpha1MultiBranchBehaviours behaviours = null;
    // orphaned setting
    V1alpha1MultiBranchPipeline multiBranch = strategy.getMultiBranch();
    if (multiBranch != null) {
      V1alpha1MultiBranchOrphan orphaned = multiBranch.getOrphaned();
      DefaultOrphanedItemStrategy orphanedStrategy;
      if (orphaned != null) {
        orphanedStrategy =
            new DefaultOrphanedItemStrategy(
                true, String.valueOf(orphaned.getDays()), String.valueOf(orphaned.getMax()));
      } else {
        orphanedStrategy = new DefaultOrphanedItemStrategy(false, "", "");
      }
      job.setOrphanedItemStrategy(orphanedStrategy);

      behaviours = multiBranch.getBehaviours();
    }

    V1alpha1PipelineSource source = pipelineConfig.getSpec().getSource();
    SCMSource scmSource = null;
    V1alpha1CodeRepositoryRef codeRepoRef = source.getCodeRepository();
    V1alpha1PipelineSourceGit gitSource = source.getGit();
    GitProviderMultiBranch gitProvider = null;
    // TODO maybe put some redundancy into annotation
    if (codeRepoRef != null) {
      // cases for git provider
      String codeRepoName = codeRepoRef.getName();

      V1alpha1CodeRepository codeRep =
          Clients.get(V1alpha1CodeRepository.class)
              .lister()
              .namespace(namespace)
              .get(codeRepoRef.getName());
      if (codeRep != null) {
        V1alpha1CodeRepositorySpec codeRepoSpec = codeRep.getSpec();
        V1alpha1OriginCodeRepository codeRepo = codeRepoSpec.getRepository();
        String[] repoFullName = codeRepo.getFullName().split("/");
        String repository = repoFullName[repoFullName.length - 1];
        String repoOwner =
            String.join("/", Arrays.copyOfRange(repoFullName, 0, repoFullName.length - 1));
        String codeRepoType = codeRepo.getCodeRepoServiceType();

        Optional<GitProviderMultiBranch> gitProviderOpt =
            Jenkins.getInstance()
                .getExtensionList(GitProviderMultiBranch.class)
                .stream()
                .filter(git -> git.accept(codeRepo.getCodeRepoServiceType()))
                .findFirst();
        boolean supported = gitProviderOpt.isPresent();
        if (supported) {
          // TODO need to deal with the private git providers
          gitProvider = gitProviderOpt.get();
          if (gitProvider instanceof PrivateGitProviderMultiBranch) {
            PrivateGitProviderMultiBranch privateGitProvider =
                (PrivateGitProviderMultiBranch) gitProvider;
            // The server name should be the same as the codeRepoService name
            String serverName = codeRep.getMetadata().getLabels().get("codeRepoService");
            scmSource = privateGitProvider.getSCMSource(serverName, repoOwner, repository);
          } else {
            scmSource = gitProvider.getSCMSource(repoOwner, repository);
          }
          if (scmSource == null) {
            logger.warn(
                "Can't create instance for AbstractGitSCMSource. Type is {}.", codeRepoType);
            // TODO add a downgrade strategy
            gitProvider = null;
          }
        }
        if (scmSource == null) {
          // TODO should take care of clean up job
          logger.warn(
              "Not support for {}, codeRepo name is {}. Fall back to general git.",
              codeRepoType,
              codeRepoName);

          scmSource = new GitSCMSource(codeRepo.getCloneURL());
        }
      } else {
        logger.warn("Can't found codeRepository {}, namespace {}.", codeRepoName, namespace);
      }
    } else if (gitSource != null) {
      // general git
      scmSource = new GitSCMSource(gitSource.getUri());
    } else {
      logger.warn("Not found git repository.");
    }

    // handle common settings
    if (scmSource != null) {
      handleSCMTraits(scmSource, behaviours, gitProvider);

      handleCredentials(scmSource, pipelineConfig);

      job.setSourcesList(Collections.singletonList(new BranchSource(scmSource)));
      scmSource.setOwner(job);
      scmSource.afterSave();
    }

    List<V1alpha1PipelineTrigger> triggers = pipelineConfig.getSpec().getTriggers();
    if (triggers != null) {
      Optional<V1alpha1PipelineTrigger> triggerOpt =
          triggers
              .stream()
              .filter(trigger -> PIPELINE_TRIGGER_TYPE_CRON.equals(trigger.getType()))
              .findFirst();
      if (triggerOpt.isPresent()) {
        V1alpha1PipelineTrigger trigger = triggerOpt.get();
        V1alpha1PipelineTriggerCron cron = trigger.getCron();

        try {
          job.addTrigger(new CronFolderTrigger(cron.getRule(), cron.isEnabled()));
        } catch (ANTLRException e) {
          e.printStackTrace();
        }
      }
    }

    Map<String, String> logURLs =
        Collections.singletonMap(
            ALAUDA_DEVOPS_ANNOTATIONS_MULTI_BRANCH_SCAN_LOG.get().toString(),
            String.format(
                "/job/%s/job/%s/indexing/logText/progressiveText",
                namespace, mapper.jenkinsJobName(namespace, name)));
    addAnnotations(pipelineConfig, logURLs);

    return job;
  }

  // TODO should create a PR to unit the interface
  private void handleSCMTraits(
      @NotNull SCMSource source,
      V1alpha1MultiBranchBehaviours behaviours,
      GitProviderMultiBranch gitProvider) {
    List<SCMSourceTrait> traits = new ArrayList<>();
    if (behaviours != null && StringUtils.isNotBlank(behaviours.getFilterExpression())) {
      traits.add(new RegexSCMHeadFilterTrait(behaviours.getFilterExpression()));
    }

    if (gitProvider != null) {
      traits.add(gitProvider.getBranchDiscoverTrait(1));
      traits.add(gitProvider.getOriginPRTrait(1));
      traits.add(gitProvider.getForkPRTrait(1));
      traits.add(gitProvider.getCloneTrait());
    } else {
      traits.add(new BranchDiscoveryTrait());
    }

    try {
      Method method = source.getClass().getMethod("setTraits", List.class);
      method.invoke(source, traits);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      e.printStackTrace();
      logger.error(String.format("Can't setting traits, source class is %s", source.getClass()));
    }
  }

  // TODO should create a PR to unit the interface
  private void handleCredentials(
      @NotNull SCMSource source, @NotNull V1alpha1PipelineConfig pipelineConfig)
      throws IOException {
    String credentialId;
    try {
      credentialId = CredentialsUtils.getSCMSourceCredentialsId(pipelineConfig);

      Method method = source.getClass().getMethod("setCredentialsId", String.class);
      method.invoke(source, credentialId);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      e.printStackTrace();
      logger.error(
          String.format("Can't setting credentials, source class is %s", source.getClass()));
    }
  }

  private void addAnnotations(
      @NotNull V1alpha1PipelineConfig pc, @NotNull Map<String, String> annotations) {
    V1alpha1PipelineConfig oldPc = DeepCopyUtils.deepCopy(pc);

    annotations.forEach((key, value) -> pc.getMetadata().putAnnotationsItem(key, value));
    Clients.get(V1alpha1PipelineConfig.class).update(oldPc, pc);
  }
}
