package io.alauda.jenkins.devops.sync;

import hudson.ExtensionPoint;
import hudson.model.AbstractItem;
import hudson.model.TopLevelItem;
import io.alauda.devops.java.client.models.V1alpha1Condition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigStatus;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigStatusBuilder;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.constants.PipelineConfigPhase;
import io.alauda.jenkins.devops.sync.controller.PipelineConfigController;
import org.joda.time.DateTime;

import javax.validation.constraints.NotNull;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public interface PipelineConfigConvert<T extends TopLevelItem> extends ExtensionPoint {
    boolean accept(V1alpha1PipelineConfig pipelineConfig);

    T convert(V1alpha1PipelineConfig pipelineConfig) throws IOException;

    default boolean isSameJob(V1alpha1PipelineConfig pipelineConfig, AlaudaJobProperty jobProperty) {
        return pipelineConfig.getMetadata().getUid().equals(jobProperty.getUid());
    }

    default void updateJob(AbstractItem item, InputStream jobStream, String jobName, V1alpha1PipelineConfig pipelineConfig) throws IOException {
        Source source = new StreamSource(jobStream);
        item.updateByXml(source);
        item.save();
    }

    default void updatePipelineConfigPhase(@NotNull final V1alpha1PipelineConfig pipelineConfig) {

        V1alpha1PipelineConfigStatus status = pipelineConfig.getStatus();
        List<V1alpha1Condition> conditions = status.getConditions();

        if (conditions.size() > 0) {
            V1alpha1PipelineConfigStatusBuilder statusBuilder = new V1alpha1PipelineConfigStatusBuilder();

            conditions.forEach(condition -> {
                condition.setLastAttempt(new DateTime());
                statusBuilder.addNewConditionLike(condition).endCondition();
            });

            statusBuilder.withMessage("Exists errors in process of creating pipeline job.");
            statusBuilder.withPhase(PipelineConfigPhase.ERROR);
            V1alpha1PipelineConfig oldPipelineConfig = DeepCopyUtils.deepCopy(pipelineConfig);

            statusBuilder.withLastUpdated(DateTime.now());
            pipelineConfig.status(statusBuilder.build());

            PipelineConfigController.updatePipelineConfig(oldPipelineConfig, pipelineConfig);
        } else {
            V1alpha1PipelineConfig oldPipelineConfig = DeepCopyUtils.deepCopy(pipelineConfig);
            pipelineConfig.getStatus().setPhase(PipelineConfigPhase.READY);

            PipelineConfigController.updatePipelineConfig(oldPipelineConfig, pipelineConfig);
        }
    }
}
