package io.alauda.jenkins.devops.sync.util;

import hudson.Plugin;
import hudson.util.VersionNumber;
import io.alauda.jenkins.devops.sync.WatcherAliveCheck;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.constants.ErrorMessages;
import io.alauda.kubernetes.api.model.Condition;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigTemplate;
import io.alauda.kubernetes.api.model.PipelineDependency;
import jenkins.model.Jenkins;

import javax.annotation.Nonnull;
import javax.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINECONFIG_KIND_MULTI_BRANCH;

public abstract class PipelineConfigUtils {
    private static final Logger logger = Logger.getLogger(PipelineConfigUtils.class.getName());

    private PipelineConfigUtils(){}

    public static boolean isSerialPolicy(PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            throw new IllegalArgumentException("param can't be null");
        }

        return Constants.PIPELINE_RUN_POLICY_SERIAL.equals(pipelineConfig.getSpec().getRunPolicy());
    }

    public static boolean isParallel(PipelineConfig pipelineConfig) {
        if(pipelineConfig == null) {
            throw new IllegalArgumentException("param can't be null");
        }

        return Constants.PIPELINE_RUN_POLICY_PARALLEL.equals(pipelineConfig.getSpec().getRunPolicy());
    }

    /**
     * Check PipelineConfig dependency
     * @param pipelineConfig PipelineConfig
     * @param conditions condition list
     */
    public static void dependencyCheck(@Nonnull PipelineConfig pipelineConfig, @Nonnull List<Condition> conditions) {
        boolean fromTpl = createFromTpl(pipelineConfig);
        if(!fromTpl) {
            // just care about template case
            return;
        }

        PipelineConfigTemplate template = pipelineConfig.getSpec().getStrategy().getTemplate();
        PipelineDependency dependencies = template.getSpec().getDependencies();
        if(dependencies == null || CollectionUtils.isEmpty(dependencies.getPlugins())) {
            logger.info("PipelineConfig " + pipelineConfig.getMetadata().getName() + " no any dependencies.");
            return;
        }

        final Jenkins jenkins = Jenkins.getInstance();
        dependencies.getPlugins().forEach(plugin -> {
            String name = plugin.getName();
            String version = plugin.getVersion();
            VersionNumber verNumber = new VersionNumber(version);
            VersionNumber currentNumber;

            Condition condition = new Condition();
            condition.setReason(ErrorMessages.PLUGIN_ERROR);

            Plugin existsPlugin = jenkins.getPlugin(name);
            if (existsPlugin == null) {

                condition.setMessage(String.format("Lack plugin: %s, version: %s", name, version));
            } else {
                currentNumber = existsPlugin.getWrapper().getVersionNumber();

                if (currentNumber.isOlderThan(verNumber)) {
                    condition.setMessage(
                            String.format("Require plugin: %s, version: %s, found %s", name, version, currentNumber));
                }
            }

            if(condition.getMessage() != null) {
                conditions.add(condition);
            }
        });
    }

    /**
     * Whether PipelineConfig is create from a template
     * @param pipelineConfig PipelineConfig
     * @return whether PipelineConfig is create from a template
     */
    public static boolean createFromTpl(@Nonnull PipelineConfig pipelineConfig) {
        PipelineConfigTemplate template = pipelineConfig.getSpec().getStrategy().getTemplate();

        return template != null && template.getSpec() != null;
    }

    public static boolean isMultiBranch(@NotNull PipelineConfig pipelineConfig) {
        Map<String, String> labels = pipelineConfig.getMetadata().getLabels();
        return (labels != null && PIPELINECONFIG_KIND_MULTI_BRANCH.equals(labels.get(PIPELINECONFIG_KIND)));
    }
}
