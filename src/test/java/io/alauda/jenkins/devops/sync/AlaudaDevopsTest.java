package io.alauda.jenkins.devops.sync;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Ignore;
import org.junit.Test;
import org.omg.CORBA.NameValuePair;

import static junit.framework.TestCase.assertNotNull;

public class AlaudaDevopsTest {
    public static final String JENKINS_SERVICE = "service-for-test";
    public static final String NAMESPACE = "test";

    @Test
    @Ignore
    public void testListJenkinsBindings() {
        AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
        Config config = configBuilder.build();
        AlaudaDevOpsClient client = new DefaultAlaudaDevOpsClient(config);

        String namespace = createNamespace(client).getMetadata().getName();

        String jenkinsName = createJenkins(client).getMetadata().getName();

        String secretName = createSecret(client, namespace).getMetadata().getName();

        String bindingName = createBinding(client, namespace, secretName, jenkinsName)
                .getMetadata().getName();

        client.pipelineConfigs()
                .createNew()
                .withNewMetadata()
                .withGenerateName("a")
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

    public JenkinsBinding createBinding(AlaudaDevOpsClient client, String namespace, String secretName, String jenkinsName) {
//        SecretKeySetRef secret = new SecretKeySetRefBuilder()
//                .withApiTokenKey("a")
//                .withName(secretName)
//                .withUsernameKey("a")
//                .build();

//        JenkinsInstance jenkins = new JenkinsInstanceBuilder()
//                .withName(jenkinsName)
//                .build();

//        JenkinsBindingSpec spec = new JenkinsBindingSpecBuilder()
//                .withNewAccount()
//                .withSecret(secret)
//                .endAccount()
//                .withJenkins(jenkins)
//                .build();

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

    public Secret createSecret(AlaudaDevOpsClient client, String namespace) {
        return client.secrets()
                .createNew()
                .withNewMetadata()
                .withNamespace(namespace)
                .withName("secret")
                .endMetadata()
                .done();
    }

    public Namespace createNamespace(AlaudaDevOpsClient client) {
        return client.namespaces().createNew()
                .withNewMetadata()
                .withGenerateName("ns-")
                .endMetadata()
                .done();
    }
}
