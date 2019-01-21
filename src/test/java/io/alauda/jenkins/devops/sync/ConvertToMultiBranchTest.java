package io.alauda.jenkins.devops.sync;

import io.alauda.jenkins.devops.sync.util.AlaudaUtils;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigBuilder;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;

public class ConvertToMultiBranchTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void test() throws IOException {
        ConvertToMultiBranch convert = new ConvertToMultiBranch();
        AlaudaUtils.initializeAlaudaDevOpsClient(null);

        PipelineConfig pipelineconfig = new PipelineConfigBuilder()
                .withNewMetadata().withName("multi")
                .addToLabels("pipeline.kind", "multi-branch")
                .withNamespace("zxj").endMetadata()
                .withNewSpec()
                .withNewSource()
                .withNewCodeRepository("github-surenpi-demo", "master")
                .endSource()
                .withNewStrategy().withNewJenkins()
                .withJenkinsfilePath("Jenkinsfile").endJenkins().endStrategy()
                .endSpec()
                .build();
        convert.convert(pipelineconfig);
    }
}
