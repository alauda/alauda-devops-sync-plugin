package io.alauda.jenkins.devops.sync.controller.predicates;

import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsBindingClient;

import java.util.function.Predicate;

public class BindResourcePredicate {

    public static boolean isBindedResource(String namespace, String jenkinsBindingName) {
        String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
        JenkinsBindingClient client = (JenkinsBindingClient) Clients.get(V1alpha1JenkinsBinding.class);
        return client.lister()
                .list()
                .stream()
                .filter(jenkinsBinding -> jenkinsBinding.getSpec().getJenkins().getName().equals(jenkinsService) && jenkinsBinding.getMetadata().getNamespace().equals(namespace))
                .anyMatch(jenkinsBinding -> jenkinsBinding.getMetadata().getName().equals(jenkinsBindingName));
    }
}
