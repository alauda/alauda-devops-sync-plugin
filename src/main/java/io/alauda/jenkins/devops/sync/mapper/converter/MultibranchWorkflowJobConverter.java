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
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.folder.CronFolderTrigger;
import io.alauda.jenkins.devops.sync.mapper.PipelineConfigMapper;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;
import javax.annotation.Nonnull;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMSourceTrait;
import jenkins.scm.impl.trait.RegexSCMHeadFilterTrait;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
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

  public WorkflowMultiBranchProject convert(V1alpha1PipelineConfig pipelineConfig)
      throws IOException, PipelineConfigConvertException {
    WorkflowMultiBranchProject job = getOrCreateWorkflowMultiBranchProject(pipelineConfig);

    MultiBranchProperty mbProperty = job.getProperties().get(MultiBranchProperty.class);
    mbProperty.setContextAnnotation(mbProperty.generateAnnotationAsJSON(pipelineConfig));
    mbProperty.setResourceVersion(pipelineConfig.getMetadata().getResourceVersion());

    V1alpha1PipelineStrategyJenkins jenkinsStrategy =
        pipelineConfig.getSpec().getStrategy().getJenkins();
    if (jenkinsStrategy == null || StringUtils.isEmpty(jenkinsStrategy.getJenkinsfilePath())) {
      throw new PipelineConfigConvertException(
          String.format(
              "Unable to update Jenkins Job %s, Jenkinsfile path is null", job.getFullName()));
    }

    BranchProjectFactory factory = job.getProjectFactory();
    if (!(factory instanceof WorkflowBranchProjectFactory)) {
      throw new PipelineConfigConvertException(
          String.format(
              "Unable to update Jenkins Job %s, except a WorkflowBranchProjectFactory, but found a %s",
              job.getFullName(), factory.getClass().getName()));
    }
    ((WorkflowBranchProjectFactory) factory).setScriptPath(jenkinsStrategy.getJenkinsfilePath());

    setupOrphanedStrategy(job, pipelineConfig);
    setupSCMSource(job, pipelineConfig);
    setUpTriggers(job, pipelineConfig);

    String namespace = pipelineConfig.getMetadata().getNamespace();
    String name = pipelineConfig.getMetadata().getName();
    Map<String, String> logURLs =
        Collections.singletonMap(
            ALAUDA_DEVOPS_ANNOTATIONS_MULTI_BRANCH_SCAN_LOG.get().toString(),
            String.format(
                "/job/%s/job/%s/indexing/logText/progressiveText",
                namespace, mapper.jenkinsJobName(namespace, name)));
    addAnnotations(pipelineConfig, logURLs);

    return job;
  }

  private void setupSCMSource(WorkflowMultiBranchProject job, V1alpha1PipelineConfig pipelineConfig)
      throws PipelineConfigConvertException, IOException {
    logger.debug("Starting setup SCMSource for Workflow job {}", job.getFullName());

    V1alpha1PipelineSource source = pipelineConfig.getSpec().getSource();
    if (source.getCodeRepository() == null && source.getGit() == null) {
      throw new PipelineConfigConvertException("No Git Repository found");
    }

    GitProviderMultiBranch gitProvider = null;
    SCMSource scmSource = getCurrentSCMSource(job);

    if (source.getCodeRepository() == null && source.getGit() != null) {
      logger.debug("No CodeRepository configured in PipelineConfig, fallback to use plain git url");
      if (!(scmSource instanceof GitSCMSource)
          || ((GitSCMSource) scmSource).getRemote().equals(source.getGit().getUri())) {
        scmSource = setNewSCMSource(job, new GitSCMSource(source.getGit().getUri()));
      }
    } else {
      V1alpha1CodeRepository codeRepository =
          Clients.get(V1alpha1CodeRepository.class)
              .lister()
              .namespace(pipelineConfig.getMetadata().getNamespace())
              .get(source.getCodeRepository().getName());

      if (codeRepository == null) {
        throw new PipelineConfigConvertException(
            String.format(
                "Unable to sync PipelineConfig, No CodeRepository '%s/%s' found in platform",
                pipelineConfig.getMetadata().getNamespace(), source.getCodeRepository().getName()));
      }

      V1alpha1OriginCodeRepository originCodeRepository = codeRepository.getSpec().getRepository();
      String[] repoFullName = originCodeRepository.getFullName().split("/");
      String repository = repoFullName[repoFullName.length - 1];
      String repoOwner =
          String.join("/", Arrays.copyOfRange(repoFullName, 0, repoFullName.length - 1));
      String codeRepoType = originCodeRepository.getCodeRepoServiceType();

      gitProvider =
          Jenkins.get()
              .getExtensionList(GitProviderMultiBranch.class)
              .stream()
              .filter(git -> git.accept(codeRepoType))
              .findFirst()
              .orElse(null);
      if (gitProvider != null) {
        logger.debug(
            "GitProvider {} found, will set or update SCMSource", gitProvider.getClass().getName());
        if (scmSource == null) {
          if (gitProvider instanceof PrivateGitProviderMultiBranch) {
            // The server name should be the same as the codeRepoService name
            String serverName = codeRepository.getMetadata().getLabels().get("codeRepoService");
            scmSource =
                ((PrivateGitProviderMultiBranch) gitProvider)
                    .getSCMSource(serverName, repoOwner, repository);
          } else {
            scmSource = gitProvider.getSCMSource(repoOwner, repository);
          }

          // if we cannot create SCMSource, we will fallback to use GitSCMSource
          if (scmSource == null) {
            scmSource = new GitSCMSource(source.getGit().getUri());
            gitProvider = null;
          }

          setNewSCMSource(job, scmSource);
        } else {
          SCMSource expectedSCMSource;
          if (gitProvider instanceof PrivateGitProviderMultiBranch) {
            String serverName = codeRepository.getMetadata().getLabels().get("codeRepoService");
            expectedSCMSource =
                ((PrivateGitProviderMultiBranch) gitProvider)
                    .getSCMSource(serverName, repoOwner, repository);
          } else {
            expectedSCMSource = gitProvider.getSCMSource(repoOwner, repository);
          }

          // if we cannot create SCMSource, we will fallback to use GitSCMSource
          if (expectedSCMSource == null) {
            scmSource = setNewSCMSource(job, new GitSCMSource(source.getGit().getUri()));
            gitProvider = null;
          } else if (!gitProvider.isSourceSame(scmSource, expectedSCMSource)) {
            // if the current SCMSource is not the same repo with PipelineConfig's, we will
            // overwrite it.
            logger.debug("SCMSource is not same, will update it");
            scmSource = setNewSCMSource(job, expectedSCMSource);
          }
        }

      } else {
        // if the current SCM source is null or is not a GitSCMSource or its remote uri changed, we
        // will overwrite it
        if (!(scmSource instanceof GitSCMSource)
            || ((GitSCMSource) scmSource).getRemote().equals(source.getGit().getUri())) {
          scmSource = setNewSCMSource(job, new GitSCMSource(source.getGit().getUri()));
        }
      }
    }

    V1alpha1MultiBranchPipeline multiBranchStrategy =
        pipelineConfig.getSpec().getStrategy().getJenkins().getMultiBranch();

    handleSCMTraits(scmSource, multiBranchStrategy.getBehaviours(), gitProvider);
    handleCredentials(scmSource, pipelineConfig);
    scmSource.setOwner(job);
    scmSource.afterSave();
  }

  private SCMSource setNewSCMSource(WorkflowMultiBranchProject job, SCMSource newSCMSource) {
    job.getSourcesList().clear();
    job.getSourcesList().add(new BranchSource(newSCMSource));
    return newSCMSource;
  }

  private SCMSource getCurrentSCMSource(WorkflowMultiBranchProject job) {
    List<SCMSource> scmSources = job.getSCMSources();
    if (CollectionUtils.isEmpty(scmSources)) {
      return null;
    }

    // if there are multiple SCM sources, we only handle the first one
    return scmSources.get(0);
  }

  private void setupOrphanedStrategy(
      WorkflowMultiBranchProject job, V1alpha1PipelineConfig pipelineConfig) {
    V1alpha1MultiBranchPipeline multiBranchStrategy =
        pipelineConfig.getSpec().getStrategy().getJenkins().getMultiBranch();

    if (multiBranchStrategy == null) {
      logger.debug(
          "No MultiBranch strategy configured in PipelineConfig, Job {} will not setup orphan items strategy",
          job.getFullName());
      return;
    }

    V1alpha1MultiBranchOrphan orphanStrategyConfiguration = multiBranchStrategy.getOrphaned();
    DefaultOrphanedItemStrategy orphanedItemStrategy;
    if (orphanStrategyConfiguration == null) {
      orphanedItemStrategy = new DefaultOrphanedItemStrategy(false, "", "");
    } else {
      orphanedItemStrategy =
          new DefaultOrphanedItemStrategy(
              true,
              String.valueOf(orphanStrategyConfiguration.getDays()),
              String.valueOf(orphanStrategyConfiguration.getMax()));
    }

    job.setOrphanedItemStrategy(orphanedItemStrategy);
  }

  private WorkflowMultiBranchProject getOrCreateWorkflowMultiBranchProject(
      V1alpha1PipelineConfig pipelineConfig) throws IOException, PipelineConfigConvertException {

    String namespace = pipelineConfig.getMetadata().getNamespace();
    String name = pipelineConfig.getMetadata().getName();
    WorkflowMultiBranchProject job;
    Item item = jenkinsClient.getItem(new NamespaceName(namespace, name));
    if (item == null) {
      logger.debug(
          "Unable to found a Jenkins job for PipelineConfig '{}/{}', will create a new one",
          namespace,
          name);

      Folder parentFolder = jenkinsClient.upsertFolder(namespace);
      job = new WorkflowMultiBranchProject(parentFolder, mapper.jenkinsJobName(namespace, name));

      MultiBranchProperty property =
          new MultiBranchProperty(
              namespace,
              name,
              pipelineConfig.getMetadata().getUid(),
              pipelineConfig.getMetadata().getResourceVersion());

      job.addProperty(property);
    } else if (!(item instanceof WorkflowMultiBranchProject)) {
      throw new PipelineConfigConvertException(
          String.format(
              "Unable to update Jenkins job, except a WorkflowMultiBranchProject but found a %s",
              item.getClass()));
    } else {
      job = (WorkflowMultiBranchProject) item;
    }
    return job;
  }

  private void setUpTriggers(WorkflowMultiBranchProject job, V1alpha1PipelineConfig pipelineConfig)
      throws PipelineConfigConvertException {
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
          throw new PipelineConfigConvertException(
              String.format("Cron trigger is not legal, reason %s", e.getMessage()));
        }
      }
    }
  }

  // TODO should create a PR to unit the interface
  private void handleSCMTraits(
      @Nonnull SCMSource source,
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
      @Nonnull SCMSource source, @Nonnull V1alpha1PipelineConfig pipelineConfig)
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
      @Nonnull V1alpha1PipelineConfig pc, @Nonnull Map<String, String> annotations) {
    V1alpha1PipelineConfig oldPc = DeepCopyUtils.deepCopy(pc);

    annotations.forEach((key, value) -> pc.getMetadata().putAnnotationsItem(key, value));
    Clients.get(V1alpha1PipelineConfig.class).update(oldPc, pc);
  }
}
