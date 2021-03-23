package io.alauda.jenkins.devops.sync.mapper.converter;

import static io.alauda.jenkins.devops.sync.constants.Constants.ALAUDA_DEVOPS_ANNOTATIONS_MULTI_BRANCH_SCAN_LOG;
import static io.alauda.jenkins.devops.sync.constants.Constants.CONDITION_STATUS_FALSE;
import static io.alauda.jenkins.devops.sync.constants.Constants.CONDITION_STATUS_TRUE;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND_MULTI_BRANCH;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONDITION_TYPE_SUPPORT_PR_DISCOVERY;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONFIG_CONDITION_REASON_INCORRECT;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONFIG_CONDITION_REASON_SUPPORTED;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_CONFIG_CONDITION_REASON_UNSUPPORTED;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_TRIGGER_TYPE_INTERVAL;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.Folder;
import com.cloudbees.hudson.plugins.folder.computed.ComputedFolder;
import com.cloudbees.hudson.plugins.folder.computed.DefaultOrphanedItemStrategy;
import com.cloudbees.hudson.plugins.folder.computed.PeriodicFolderTrigger;
import hudson.Extension;
import hudson.model.Item;
import hudson.plugins.git.extensions.impl.CloneOption;
import io.alauda.devops.java.client.models.V1alpha1BranchBehaviour;
import io.alauda.devops.java.client.models.V1alpha1CloneBehaviour;
import io.alauda.devops.java.client.models.V1alpha1CodeRepository;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1MultiBranchBehaviours;
import io.alauda.devops.java.client.models.V1alpha1MultiBranchOrphan;
import io.alauda.devops.java.client.models.V1alpha1MultiBranchPipeline;
import io.alauda.devops.java.client.models.V1alpha1OriginCodeRepository;
import io.alauda.devops.java.client.models.V1alpha1PRBehaviour;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineSource;
import io.alauda.devops.java.client.models.V1alpha1PipelineStrategyJenkins;
import io.alauda.devops.java.client.models.V1alpha1PipelineTrigger;
import io.alauda.devops.java.client.models.V1alpha1PipelineTriggerInterval;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.MultiBranchProperty;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.mapper.PipelineConfigMapper;
import io.alauda.jenkins.devops.sync.scm.RecordLastChangeLogTrait;
import io.alauda.jenkins.devops.sync.util.ConditionUtils;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.annotation.Nonnull;
import jenkins.branch.BranchProjectFactory;
import jenkins.branch.BranchSource;
import jenkins.model.Jenkins;
import jenkins.plugins.git.GitSCMSource;
import jenkins.plugins.git.traits.BranchDiscoveryTrait;
import jenkins.plugins.git.traits.CloneOptionTrait;
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
      throws PipelineConfigConvertException {
    logger.debug("Starting setup SCMSource for Workflow job {}", job.getFullName());

    V1alpha1Condition prSupportCondition =
        ConditionUtils.getCondition(
            pipelineConfig.getStatus().getConditions(),
            PIPELINE_CONDITION_TYPE_SUPPORT_PR_DISCOVERY);
    if (prSupportCondition == null) {
      throw new PipelineConfigConvertException("No PR support condition found");
    }

    V1alpha1PipelineSource source = pipelineConfig.getSpec().getSource();
    if (source.getCodeRepository() == null && source.getGit() == null) {
      throw new PipelineConfigConvertException("No Git Repository found");
    }

    GitProviderMultiBranch gitProvider = null;
    SCMSource scmSource = getCurrentSCMSource(job);

    if (source.getCodeRepository() == null && source.getGit() != null) {
      logger.debug("No CodeRepository configured in PipelineConfig, fallback to use plain git url");
      if (!(scmSource instanceof GitSCMSource)
          || !((GitSCMSource) scmSource).getRemote().equals(source.getGit().getUri())) {
        scmSource = setNewSCMSource(job, new GitSCMSource(source.getGit().getUri()));
      }
      prSupportCondition
          .status(CONDITION_STATUS_FALSE)
          .reason(PIPELINE_CONFIG_CONDITION_REASON_UNSUPPORTED)
          .message("PR Discovery not support: this pipeline is using plain git url");
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
      String cloneURL = originCodeRepository.getCloneURL();

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
            scmSource = new GitSCMSource(cloneURL);
            gitProvider = null;
            prSupportCondition
                .status(CONDITION_STATUS_FALSE)
                .reason(PIPELINE_CONFIG_CONDITION_REASON_INCORRECT)
                .message("PR Discovery not support: configuration in Jenkins might be wrong");
          } else {
            prSupportCondition
                .status(CONDITION_STATUS_TRUE)
                .reason(PIPELINE_CONFIG_CONDITION_REASON_SUPPORTED);
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
            expectedSCMSource = new GitSCMSource(cloneURL);
            // if current SCMSource is not the same with the expectedSCMSource, we will overwrite it
            if (!scmSource.getClass().equals(GitSCMSource.class)
                || !((GitSCMSource) expectedSCMSource)
                    .getRemote()
                    .equals(((GitSCMSource) scmSource).getRemote())) {
              scmSource = setNewSCMSource(job, expectedSCMSource);
            }

            gitProvider = null;
            prSupportCondition
                .status(CONDITION_STATUS_FALSE)
                .reason(PIPELINE_CONFIG_CONDITION_REASON_INCORRECT)
                .message("PR Discovery not support: configuration in Jenkins might be wrong");
          } else {
            prSupportCondition
                .status(CONDITION_STATUS_TRUE)
                .reason(PIPELINE_CONFIG_CONDITION_REASON_SUPPORTED);
            if (!gitProvider.isSourceSame(scmSource, expectedSCMSource)) {
              // if the current SCMSource is not the same repo with PipelineConfig's, we will
              // overwrite it.
              logger.debug("SCMSource is not same, will update it");
              scmSource = setNewSCMSource(job, expectedSCMSource);
            }
          }
        }

      } else {
        // if the current SCM source is null or is not a GitSCMSource or its remote uri changed, we
        // will overwrite it
        if (!(scmSource instanceof GitSCMSource)
            || !((GitSCMSource) scmSource).getRemote().equals(cloneURL)) {
          scmSource = setNewSCMSource(job, new GitSCMSource(cloneURL));
        }
        prSupportCondition
            .status(CONDITION_STATUS_FALSE)
            .reason(PIPELINE_CONFIG_CONDITION_REASON_UNSUPPORTED)
            .message("PR Discovery not support: Jenkins doesn't support this repository type");
      }
    }

    V1alpha1MultiBranchPipeline multiBranchStrategy =
        pipelineConfig.getSpec().getStrategy().getJenkins().getMultiBranch();

    // keep compatibility
    if (!StringUtils.isEmpty(multiBranchStrategy.getBehaviours().getFilterExpression())) {
      handleSCMTraits(scmSource, multiBranchStrategy.getBehaviours(), gitProvider);
    } else {
      handleSCMTraits(scmSource, multiBranchStrategy, gitProvider);
    }
    handleCredentials(scmSource, pipelineConfig);
    scmSource.setOwner(job);
    scmSource.afterSave();
  }

  private SCMSource setNewSCMSource(WorkflowMultiBranchProject job, SCMSource newSCMSource)
      throws PipelineConfigConvertException {
    try {
      job.setSourcesList(Collections.singletonList(new BranchSource(newSCMSource)));
    } catch (IOException e) {
      throw new PipelineConfigConvertException(e.getMessage());
    }

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
    if (orphanStrategyConfiguration == null || !orphanStrategyConfiguration.getEnabled()) {
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
      property.setConfiguredDefaultResume(true);

      job.addProperty(property);
    } else if (!(item instanceof WorkflowMultiBranchProject)) {
      throw new PipelineConfigConvertException(
          String.format(
              "Unable to update Jenkins job, except a WorkflowMultiBranchProject but found a %s",
              item.getClass()));
    } else {
      job = (WorkflowMultiBranchProject) item;
    }

    boolean jobIsDisabled = job.isDisabled();
    if (!pipelineConfig.getSpec().getDisabled().equals(jobIsDisabled)) {
      try {
        Method methodSetDisabled =
            ComputedFolder.class.getDeclaredMethod("setDisabled", boolean.class);
        methodSetDisabled.setAccessible(true);
        methodSetDisabled.invoke(job, pipelineConfig.getSpec().getDisabled());
      } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
        logger.error("set job disable failed", e);
      }
    }

    return job;
  }

  private void setUpTriggers(WorkflowMultiBranchProject job, V1alpha1PipelineConfig pipelineConfig)
      throws PipelineConfigConvertException {
    Optional<PeriodicFolderTrigger> existPeriodTrigger =
        job.getTriggers()
            .values()
            .stream()
            .filter(trigger -> trigger instanceof PeriodicFolderTrigger)
            .map(trigger -> (PeriodicFolderTrigger) trigger)
            .findAny();
    // remove period trigger if exists
    existPeriodTrigger.ifPresent(job::removeTrigger);

    List<V1alpha1PipelineTrigger> triggers = pipelineConfig.getSpec().getTriggers();
    if (CollectionUtils.isEmpty(triggers)) {
      return;
    }

    Optional<V1alpha1PipelineTrigger> triggerOpt =
        triggers
            .stream()
            .filter(trigger -> PIPELINE_TRIGGER_TYPE_INTERVAL.equals(trigger.getType()))
            .findFirst();

    if (!triggerOpt.isPresent()) {
      return;
    }

    V1alpha1PipelineTrigger trigger = triggerOpt.get();
    V1alpha1PipelineTriggerInterval interval = trigger.getInterval();

    if (!interval.getEnabled()) {
      return;
    }

    try {
      job.addTrigger(new PeriodicFolderTrigger(interval.getInterval()));
    } catch (ANTLRException e) {
      throw new PipelineConfigConvertException(
          String.format("Interval trigger is not legal, reason %s", e.getMessage()));
    }
  }

  // TODO should create a PR to unit the interface
  private void handleSCMTraits(
      @Nonnull SCMSource source,
      V1alpha1MultiBranchBehaviours behaviours,
      GitProviderMultiBranch gitProvider)
      throws PipelineConfigConvertException {
    List<SCMSourceTrait> traits = new ArrayList<>();
    if (behaviours != null && StringUtils.isNotBlank(behaviours.getFilterExpression())) {
      try {
        Pattern.compile(behaviours.getFilterExpression());
      } catch (PatternSyntaxException e) {
        throw new PipelineConfigConvertException(
            String.format("Unable to parse Branch discovery rules, reason %s", e.getMessage()));
      }

      traits.add(new RegexSCMHeadFilterTrait(behaviours.getFilterExpression()));
    }

    if (gitProvider != null) {
      traits.add(gitProvider.getBranchDiscoverTrait(1));
      traits.add(gitProvider.getOriginPRTrait(1));
      traits.add(gitProvider.getForkPRTrait(1));
      traits.add(gitProvider.getCloneTrait(null));
    } else {
      traits.add(new BranchDiscoveryTrait());
    }

    traits.add(new RecordLastChangeLogTrait());
    setTraits(source, traits);
  }

  private void setTraits(@Nonnull SCMSource source, List<SCMSourceTrait> traits) {
    try {
      Method method = source.getClass().getMethod("setTraits", List.class);
      method.invoke(source, traits);
    } catch (NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
      e.printStackTrace();
      logger.error(String.format("Can't setting traits, source class is %s", source.getClass()));
    }
  }

  // TODO should create a PR to unit the interface
  private void handleSCMTraits(
      @Nonnull SCMSource source,
      V1alpha1MultiBranchPipeline behaviours,
      GitProviderMultiBranch gitProvider)
      throws PipelineConfigConvertException {

    List<SCMSourceTrait> traits = new ArrayList<>();
    List<String> rules = new LinkedList<>();

    V1alpha1BranchBehaviour branchBehaviours = behaviours.getBranchBehaviour();
    if (branchBehaviours != null && branchBehaviours.getEnabled()) {
      rules.addAll(branchBehaviours.getRules());

      if (gitProvider != null) {
        if (branchBehaviours.getExcludeBranchFiledAsPR()) {
          traits.add(gitProvider.getBranchDiscoverTrait(1));
        } else {
          traits.add(gitProvider.getBranchDiscoverTrait(3));
        }
      } else {
        traits.add(new BranchDiscoveryTrait());
      }
    }

    V1alpha1PRBehaviour prBehaviour = behaviours.getPrBehaviour();
    if (gitProvider != null && prBehaviour != null && prBehaviour.getEnabled()) {
      rules.add("PR-.*");
      rules.add("MR-.*");

      if (prBehaviour.getExecuteMerged() && prBehaviour.getExecuteOriginal()) {
        traits.add(gitProvider.getOriginPRTrait(3));
      } else if (prBehaviour.getExecuteMerged()) {
        traits.add(gitProvider.getOriginPRTrait(1));
      } else if (prBehaviour.getExecuteOriginal()) {
        traits.add(gitProvider.getOriginPRTrait(2));
      }
    }

    V1alpha1CloneBehaviour cloneBehaviour = behaviours.getCloneBehaviour();
    if (cloneBehaviour != null) {
      CloneOption cloneOption =
          new CloneOption(
              cloneBehaviour.getShallowClone(),
              !cloneBehaviour.getFetchTags(),
              null,
              cloneBehaviour.getTimeout());
      if (gitProvider != null) {
        traits.add(gitProvider.getCloneTrait(cloneOption));
      } else {
        traits.add(new CloneOptionTrait(cloneOption));
      }
    }

    String regexRule = String.join("|", rules);

    try {
      Pattern.compile(regexRule);
    } catch (PatternSyntaxException e) {
      throw new PipelineConfigConvertException(
          String.format("Unable to parse Branch discovery rules, reason %s", e.getMessage()));
    }

    traits.add(new RecordLastChangeLogTrait());
    traits.add(new RegexSCMHeadFilterTrait(regexRule));

    setTraits(source, traits);
  }

  // TODO should create a PR to unit the interface
  private void handleCredentials(
      @Nonnull SCMSource source, @Nonnull V1alpha1PipelineConfig pipelineConfig) {
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
