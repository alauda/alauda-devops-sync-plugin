package io.alauda.jenkins.devops.sync;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.alauda.devops.client.models.PipelineConfigStatus.PipelineConfigPhaseCreating;

public class DevOpsInit implements Closeable {
    private String namespace;
    private String jenkinsName;
    private String secretName;
    private String bindingName;

    public AlaudaDevOpsClient getClient() {
        AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
        Config config = configBuilder.build();
        return new DefaultAlaudaDevOpsClient(config);
    }

    public DevOpsInit init() {
        AlaudaDevOpsClient client = getClient();

        namespace = createNamespace(client).getMetadata().getName();
        jenkinsName = createJenkins(client).getMetadata().getName();
        secretName = createSecret(client).getMetadata().getName();
        bindingName = createBinding(client)
                .getMetadata().getName();

        return this;
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client) {
        return client.pipelineConfigs()
                .createNew()
                .withNewMetadata()
                .withGenerateName("pipeline-config-")
                .withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewStrategy()
                .withNewJenkins().withJenkinsfile("a").endJenkins()
                .endStrategy()
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy("Serial")
                .endSpec()
                .done();
    }

    public PipelineConfig updatePipelineConfigWithParams(AlaudaDevOpsClient client,
                                                         String name,
                                                         Map<String, String> paramMap,
                                                         String script) {
        List<PipelineParameter> params = convertTo(paramMap);

        PipelineStrategy strategy = new PipelineStrategyBuilder()
                .editJenkins()
                .withJenkinsfile(script).endJenkins().editJenkins().endJenkins().build();

        client.pipelineConfigs().inNamespace(namespace).withName(name)
                .edit()
                .editSpec()
                .withStrategy(strategy)
                .withParameters(params).endSpec()
                .done();

        return null;
    }

    public PipelineConfig createPipelineConfigWithParams(AlaudaDevOpsClient client,
                                                         Map<String, String> paramMap,
                                                         String script) {
        List<PipelineParameter> params = convertTo(paramMap);

        return client.pipelineConfigs()
                .createNew()
                .withNewMetadata()
                .withGenerateName("pipeline-config-")
                .withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewStrategy()
                .withNewJenkins().withJenkinsfile(script).endJenkins()
                .endStrategy()
                .withParameters(params)
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy("Serial")
                .endSpec()
                .done();
    }

    private List<PipelineParameter> convertTo(Map<String, String> paramMap) {
        List<PipelineParameter> params = new ArrayList<>();
        paramMap.forEach((key, val) -> {
            params.add(new PipelineParameterBuilder().withName(key).withType(val).build());
        });
        return params;
    }

    public void updatePipelineConfig(AlaudaDevOpsClient client, String name, String script) {
        client.pipelineConfigs().inNamespace(namespace)
                .withName(name)
                .edit()
                .editSpec()
                .editStrategy()
                .editJenkins()
                .withJenkinsfile(script)
                .endJenkins()
                .endStrategy()
                .endSpec()
                .editStatus().withPhase(PipelineConfigPhaseCreating).endStatus()
                .done();
    }

    public PipelineConfig getPipelineConfig(AlaudaDevOpsClient client, String name) {
        return client.pipelineConfigs().inNamespace(namespace).withName(name).get();
    }

    public Pipeline createPipeline(AlaudaDevOpsClient client, String configName, Map<String, String> paramMap) {
        PipelineConfig pipelineConfig = client.pipelineConfigs().inNamespace(namespace).withName(configName).get();
        List<PipelineParameter> params = pipelineConfig.getSpec().getParameters();

        params.forEach(p -> {
            p.setValue(paramMap.get(p.getName()));
        });

        return client.pipelines().createNew()
                .withNewMetadata()
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withParameters(params)
                .withNewPipelineConfig(configName)
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy("Serial")
                .withNewStrategy().withNewJenkins("a", "a").endStrategy()
                .endSpec()
                .done();
    }

    public List<Pipeline> getPipelines(AlaudaDevOpsClient client) {
        return client.pipelines().inNamespace(namespace).list().getItems();
    }

    public Pipeline createPipeline(AlaudaDevOpsClient client, String pipelineConfig) {
        return client.pipelines().createNew()
                .withNewMetadata()
                .withNamespace(namespace)
                .endMetadata()
                .withNewSpec()
                .withNewPipelineConfig(pipelineConfig)
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy("Serial")
                .withNewStrategy().withNewJenkins("a", "a").endStrategy()
                .endSpec()
                .done();
    }

    public JenkinsBinding createBinding(AlaudaDevOpsClient client) {
        return client.jenkinsBindings()
                .createNew()
                .withNewMetadata()
                .withGenerateName("jenkins-binding-")
                .withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewJenkins().withName(jenkinsName).endJenkins()
                .withNewAccount().withNewSecret()
                .withApiTokenKey("a").withName(secretName)
                .withUsernameKey("a").endSecret().endAccount()
                .endSpec()
                .done();
    }

    public Jenkins createJenkins(AlaudaDevOpsClient client) {
        JenkinsSpec jenkinsSpec = new JenkinsSpecBuilder()
                .withNewHttp("http://abc.com")
                .build();

        return client.jenkins().createNew().withNewMetadata()
                .withGenerateName("jenkins-")
                .endMetadata()
                .withSpec(jenkinsSpec)
                .done();
    }

    public Secret createSecret(AlaudaDevOpsClient client) {
        return client.secrets()
                .createNew()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName("secret")
                .endMetadata()
                .done();
    }

    public Project createNamespace(AlaudaDevOpsClient client) {
        DoneableProject project = client.projects().createNew()
                .withNewMetadata()
                .withGenerateName("project-test-")
                .endMetadata();
        return project.done();
    }

    public String getNamespace() {

        return namespace;
    }

    public String getJenkinsName() {
        return jenkinsName;
    }

    public String getSecretName() {
        return secretName;
    }

    public String getBindingName() {
        return bindingName;
    }

    @Override
    public void close() throws IOException {
        if(namespace != null) {
            getClient().projects().withName(namespace).delete();
        }

        if(jenkinsName != null) {
            getClient().jenkins().withName(jenkinsName).delete();
        }
    }
}
