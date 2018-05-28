package io.alauda.jenkins.devops.sync.watcher;

import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.Secret;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * @author suren
 */
public class ResourcesCache {
    private String jenkinsService;

    private Set<String> namespaces = new CopyOnWriteArraySet<>();
    private Set<String> pipelineConfigs = new CopyOnWriteArraySet<>();
    private Map<String, String> bindingMap = new ConcurrentHashMap<>();

    private static final ResourcesCache RESOURCES_CACHE = new ResourcesCache();

    private ResourcesCache(){}

    public static ResourcesCache getInstance() {
        return RESOURCES_CACHE;
    }

    public void setJenkinsService(String jenkinsService) {
        this.jenkinsService = jenkinsService;
    }

    public void addNamespace(String namespace) {
        namespaces.add(namespace);
    }

    public void addNamespace(JenkinsBinding jenkinsBinding) {
        String jenkinsName = jenkinsBinding.getSpec().getJenkins().getName();
        if(jenkinsName.equals(jenkinsService)) {
            String namespace = jenkinsBinding.getMetadata().getNamespace();

            addNamespace(namespace);

            addJenkinsBinding(jenkinsBinding);
        }
    }

    public void removeNamespace(String namespace) {
        namespaces.remove(namespace);
    }

    public void removeNamespace(JenkinsBinding jenkinsBinding) {
        String jenkinsName = jenkinsBinding.getSpec().getJenkins().getName();
        if(jenkinsName.equals(jenkinsService)) {
            String namespace = jenkinsBinding.getMetadata().getNamespace();

            removeNamespace(namespace);

            removeJenkinsBinding(jenkinsBinding);
        }
    }

    public void addPipelineConfig(PipelineConfig pipelineConfig) {
        String pipelineConfigName = pipelineConfig.getMetadata().getName();
        pipelineConfigs.add(pipelineConfigName);
    }

    public void addJenkinsBinding(JenkinsBinding jenkinsBinding) {
        bindingMap.put(jenkinsBinding.getMetadata().getName(),
                jenkinsBinding.getSpec().getJenkins().getName());
    }

    public void removeJenkinsBinding(JenkinsBinding jenkinsBinding) {
        bindingMap.remove(jenkinsBinding.getMetadata().getName());
    }

    public boolean isBinding(PipelineConfig pipelineConfig) {
        String bindingName = pipelineConfig.getSpec().getJenkinsBinding().getName();
        String namespace = pipelineConfig.getMetadata().getNamespace();

        return isBinding(bindingName, namespace);
    }

    public boolean isBinding(Pipeline pipeline) {
        String bindingName = pipeline.getSpec().getJenkinsBinding().getName();
        String namespace = pipeline.getMetadata().getNamespace();

        return isBinding(bindingName, namespace);
    }

    private boolean isBinding(String bindingName, String namespace) {
        String bindingService = bindingMap.get(bindingName);
        return (bindingService != null
                && bindingService.equals(jenkinsService)
                && namespaces.contains(namespace)
        );
    }

    public boolean isBinding(Secret secret) {
        String namespace = secret.getMetadata().getNamespace();

        return namespaces.contains(namespace);
    }
}
