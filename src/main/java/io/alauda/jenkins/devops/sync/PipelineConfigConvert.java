package io.alauda.jenkins.devops.sync;

import hudson.ExtensionPoint;
import hudson.model.AbstractItem;
import hudson.model.TopLevelItem;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.Condition;
import io.alauda.kubernetes.api.model.ObjectMeta;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigStatus;
import io.alauda.kubernetes.api.model.PipelineConfigStatusBuilder;

import javax.validation.constraints.NotNull;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface PipelineConfigConvert<T extends TopLevelItem> extends ExtensionPoint {
    boolean accept(PipelineConfig pipelineConfig);

    T convert(PipelineConfig pipelineConfig) throws IOException;

    default boolean isSameJob(PipelineConfig pipelineConfig, AlaudaJobProperty jobProperty) {
        return pipelineConfig.getMetadata().getUid().equals(jobProperty.getUid());
    }

    default void updateJob(AbstractItem item, InputStream jobStream, String jobName, PipelineConfig pipelineConfig) throws IOException {
        Source source = new StreamSource(jobStream);
        item.updateByXml(source);
        item.save();
//        logger.info("Updated item " + jobName + " from PipelineConfig " + NamespaceName.create(pipelineConfig) + " with revision: " + pipelineConfig.getMetadata().getResourceVersion());
    }

    default void updatePipelineConfigPhase(@NotNull final PipelineConfig pipelineConfig) {
        PipelineConfigStatusBuilder statusBuilder = new PipelineConfigStatusBuilder();

        PipelineConfigStatus status = pipelineConfig.getStatus();
        List<Condition> conditions = status.getConditions();
        if (conditions.size() > 0) {
            conditions.forEach(condition -> {
                statusBuilder.addNewConditionLike(condition).endCondition();
            });

            statusBuilder.withMessage("Exists errors in process of creating pipeline job.");
            statusBuilder.withPhase(PipelineConfigPhase.ERROR);
        } else {
            statusBuilder.withPhase(PipelineConfigPhase.READY);
        }

        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        ObjectMeta metadata = pipelineConfig.getMetadata();
        String namespace = metadata.getNamespace();
        String name = metadata.getName();

        PipelineConfig result = client.pipelineConfigs().inNamespace(namespace)
                .withName(name).edit()
                .withNewStatusLike(statusBuilder.build()).endStatus()
                .done();
//
//        logger.info(String.format("Update PipelineConfig's phase %s, name: %s",
//                result.getStatus().getPhase(), result.getMetadata().getName()));
    }
}
