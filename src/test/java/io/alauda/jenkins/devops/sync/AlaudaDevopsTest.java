package io.alauda.jenkins.devops.sync;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.kubernetes.api.model.JenkinsBindingList;
import io.alauda.kubernetes.client.Config;
import org.junit.Ignore;
import org.junit.Test;

import static junit.framework.TestCase.assertNotNull;

public class AlaudaDevopsTest {

  @Test
  @Ignore
  public void testListJenkinsBindings() {
    AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
    Config config = configBuilder.build();
    AlaudaDevOpsClient alaudaClient = new DefaultAlaudaDevOpsClient(config);

    JenkinsBindingList list = alaudaClient.jenkinsBindings().inAnyNamespace().list();
    assertNotNull(list);
    assertNotNull(list.getItems());
  }
}
