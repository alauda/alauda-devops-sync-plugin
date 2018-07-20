package io.alauda.jenkins.devops.sync;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;

import java.io.Closeable;
import java.io.IOException;

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
