package io.alauda.jenkins.devops.sync.mapper.converter;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND_MULTI_BRANCH;

import antlr.ANTLRException;
import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.Extension;
import hudson.model.Item;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.PipelineConfigProjectProperty;
import io.alauda.jenkins.devops.sync.PipelineConfigToJobMapper;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.constants.PipelineRunPolicy;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.mapper.PipelineConfigMapper;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import io.alauda.jenkins.devops.sync.util.NamespaceName;
import io.kubernetes.client.models.V1ObjectMeta;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class WorkflowJobConverter implements JobConverter<WorkflowJob> {
  private Logger logger = LoggerFactory.getLogger(WorkflowJobConverter.class.getName());

  private PipelineConfigMapper mapper;
  private JenkinsClient jenkinsClient;

  public WorkflowJobConverter() {
    mapper = new PipelineConfigMapper();
    jenkinsClient = JenkinsClient.getInstance();
  }

  @Override
  public boolean accept(V1alpha1PipelineConfig pipelineConfig) {
    if (pipelineConfig == null) {
      return false;
    }

    Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
    return (labels == null
        || !PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
  }

  @Override
  public WorkflowJob convert(V1alpha1PipelineConfig pipelineConfig)
      throws PipelineConfigConvertException, IOException {
    String namespace = pipelineConfig.getMetadata().getNamespace();
    String name = pipelineConfig.getMetadata().getName();

    NamespaceName namespaceName = new NamespaceName(namespace, name);
    Item item = jenkinsClient.getItem(namespaceName);

    WorkflowJob job;
    if (item == null) {
      logger.debug("Unable to found a Jenkins job for PipelineConfig '{}/{}'", namespace, name);
      Folder parentFolder = jenkinsClient.upsertFolder(namespace);
      job = new WorkflowJob(parentFolder, mapper.jenkinsJobName(namespace, name));

      V1ObjectMeta meta = pipelineConfig.getMetadata();
      WorkflowJobProperty property =
          new WorkflowJobProperty(
              meta.getNamespace(), meta.getName(), meta.getUid(), meta.getResourceVersion(), null);
      property.setContextAnnotation(property.generateAnnotationAsJSON(pipelineConfig));

      job.addProperty(property);
    } else {
      if (!(item instanceof WorkflowJob)) {
        throw new PipelineConfigConvertException(
            String.format(
                "Unable to update Jenkins job, except a WorkflowJob but found a %s",
                item.getClass()));
      }

      job = (WorkflowJob) item;
      WorkflowJobProperty wfJobProperty = job.getProperty(WorkflowJobProperty.class);
      if (wfJobProperty == null) {
        logger.warn(
            "Missing the AlaudaWorkflowJobProperty for PipelineConfig '{}/{}', try to find a old property.",
            namespace,
            name);

        PipelineConfigProjectProperty pcpp = job.getProperty(PipelineConfigProjectProperty.class);
        if (pcpp == null) {
          logger.warn(
              "No old property PipelineConfigProjectProperty for PipelineConfig '{}/{}', will skip add property for it.",
              namespace,
              name);

          V1ObjectMeta meta = pipelineConfig.getMetadata();
          wfJobProperty =
              new WorkflowJobProperty(
                  meta.getNamespace(),
                  meta.getName(),
                  meta.getUid(),
                  meta.getResourceVersion(),
                  null);

          job.addProperty(wfJobProperty);
        } else {
          wfJobProperty = pcpp;
        }
      }

      wfJobProperty.setContextAnnotation(wfJobProperty.generateAnnotationAsJSON(pipelineConfig));
      wfJobProperty.setResourceVersion(pipelineConfig.getMetadata().getResourceVersion());
    }

    job.setDisplayName(mapper.jenkinsDisplayName(namespace, name));

    FlowDefinition flowDefinition =
        PipelineConfigToJobMapper.mapPipelineConfigToFlow(pipelineConfig);
    if (flowDefinition == null) {
      throw new PipelineConfigConvertException(
          String.format(
              "Unable to convert PipelineConfig to Jenkins job '%s/%s'", namespace, name));
    }

    job.setDefinition(flowDefinition);
    job.setConcurrentBuild(
        !PipelineRunPolicy.SERIAL.equals(pipelineConfig.getSpec().getRunPolicy()));

    // (re)populate job param list with any parameters
    // from the PipelineConfig
    JenkinsUtils.addJobParamForPipelineParameters(
        job, pipelineConfig.getSpec().getParameters(), true);

    // Setting triggers according to pipeline config
    List<ANTLRException> triggerExceptions =
        JenkinsUtils.setJobTriggers(job, pipelineConfig.getSpec().getTriggers());
    if (triggerExceptions.size() != 0) {
      throw new PipelineConfigConvertException(
          triggerExceptions
              .stream()
              .map(Throwable::getMessage)
              .collect(Collectors.toList())
              .toArray(new String[] {}));
    }

    return job;
  }
}
