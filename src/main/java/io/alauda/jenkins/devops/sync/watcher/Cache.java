package io.alauda.jenkins.devops.sync.watcher;

import io.alauda.kubernetes.api.model.JenkinsBinding;
import io.alauda.kubernetes.api.model.Pipeline;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.Secret;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

public class Cache {
    private String jenkinsService;

    private Set<String> namespaces = new CopyOnWriteArraySet<>();
    private Set<String> pipelineConfigs = new CopyOnWriteArraySet<>();

    private static final Cache cache = new Cache();

    private Cache(){}

    public static Cache getInstance() {
        return cache;
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
        }
    }

    public void addPipelineConfig(PipelineConfig pipelineConfig) {
        String pipelineConfigName = pipelineConfig.getMetadata().getName();
        pipelineConfigs.add(pipelineConfigName);
    }

    public void addJenkinsBinding(JenkinsBinding jenkinsBinding) {
    }

    public boolean isBinding(PipelineConfig pipelineConfig) {
        String binding = pipelineConfig.getSpec().getJenkinsBinding().getName();
        if(!binding.equals(jenkinsService)) {
            return false;
        }

        String namespace = pipelineConfig.getMetadata().getNamespace();

        return namespaces.contains(namespace);
    }

    public boolean isBinding(Pipeline pipeline) {
        String binding = pipeline.getSpec().getJenkinsBinding().getName();
        if(!binding.equals(jenkinsService)) {
            return false;
        }

        String namespace = pipeline.getMetadata().getNamespace();

        return namespaces.contains(namespace);
    }

    public boolean isBinding(Secret secret) {
        String namespace = secret.getMetadata().getNamespace();

        return namespaces.contains(namespace);
    }
}
