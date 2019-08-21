package io.alauda.jenkins.devops.sync.mapper;

import hudson.model.TopLevelItem;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.jenkins.devops.sync.exception.PipelineConfigConvertException;
import io.alauda.jenkins.devops.sync.mapper.converter.JobConverter;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import org.apache.commons.lang3.StringUtils;
import org.codehaus.groovy.control.MultipleCompilationErrorsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import java.io.IOException;
import java.util.Optional;

public class PipelineConfigMapper {
    /**
     * @param namespace Namespace of PipelineConfig
     * @param name      Name of PipelineConfig
     * @return Jenkins job name for the given namespace and name of {@link io.alauda.devops.java.client.models.V1alpha1PipelineConfig}
     */
    public String jenkinsJobName(String namespace, String name) {
        return String.format("%s-%s", namespace, name);
    }

    /**
     * @param namespace Namespace of PipelineConfig
     * @param name      Name of PipelineConfig
     * @return Full jenkins job path for the given namespace and name of {@link io.alauda.devops.java.client.models.V1alpha1PipelineConfig}
     */
    public String jenkinsJobPath(String namespace, String name) {
        return String.format("%s/%s-%s", namespace, namespace, name);
    }

    public String jenkinsDisplayName(String namespace, String name) {
        return String.format("%s/%s", namespace, name);
    }

    @Nonnull
    public TopLevelItem mapTo(V1alpha1PipelineConfig pc) throws PipelineConfigConvertException, IOException {
        Optional<JobConverter> converterOpt = JobConverter.all().stream()
                .filter(p -> p.accept(pc))
                .findFirst();
        if (!converterOpt.isPresent()) {
            throw new PipelineConfigConvertException(String.format("Unable to find correspondent JobConverter for PipelineConfig '%s/%s'", pc.getMetadata().getNamespace(), pc.getMetadata().getName()));
        }
        return converterOpt.get().convert(pc);
    }

    public V1alpha1PipelineConfig mapFrom(TopLevelItem job) {
        //TODO
        return null;
    }

}
