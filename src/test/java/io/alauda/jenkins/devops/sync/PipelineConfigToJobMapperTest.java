package io.alauda.jenkins.devops.sync;

import hudson.model.BooleanParameterDefinition;
import hudson.model.StringParameterDefinition;
import hudson.model.TextParameterDefinition;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfig;
import io.alauda.devops.java.client.models.V1alpha1PipelineConfigBuilder;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.kubernetes.api.model.PipelineConfig;
import io.alauda.kubernetes.api.model.PipelineConfigBuilder;
import org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition;
import org.jenkinsci.plugins.workflow.flow.FlowDefinition;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.WithoutJenkins;

import static org.junit.Assert.*;

public class PipelineConfigToJobMapperTest {
    @Rule
    public JenkinsK8sRule j = new JenkinsK8sRule();

    @Test
    public void mapPipelineConfigToFlow() throws Exception {
        assertNull(PipelineConfigToJobMapper.mapPipelineConfigToFlow(null));
        V1alpha1PipelineConfig config = new V1alpha1PipelineConfigBuilder().withNewSpec().withNewStrategy()
                .withNewJenkins().endJenkins().endStrategy().endSpec().build();
        assertNull(PipelineConfigToJobMapper.mapPipelineConfigToFlow(config));

        config = j.getDevOpsInit().createPipelineConfig(j.getClient());
        FlowDefinition flow = PipelineConfigToJobMapper.mapPipelineConfigToFlow(config);
        assertNotNull(flow);
        assertEquals(CpsFlowDefinition.class, flow.getClass());
    }

    @Test
    @WithoutJenkins
    @WithoutK8s
    public void isSupportParamType() throws Exception {
        final StringParameterDefinition strParamDef = new StringParameterDefinition("", "");
        final BooleanParameterDefinition boolParamDef = new BooleanParameterDefinition("", false,"");
        final TextParameterDefinition textParamDef = new TextParameterDefinition("", "", "");

        assertTrue(PipelineConfigToJobMapper.isSupportParamType(strParamDef));
        assertTrue(PipelineConfigToJobMapper.isSupportParamType(boolParamDef));
        assertFalse(PipelineConfigToJobMapper.isSupportParamType(textParamDef));

        assertEquals(Constants.PIPELINE_PARAMETER_TYPE_STRING, PipelineConfigToJobMapper.paramType(strParamDef));
        assertEquals(Constants.PIPELINE_PARAMETER_TYPE_BOOLEAN, PipelineConfigToJobMapper.paramType(boolParamDef));
        assertNull(PipelineConfigToJobMapper.paramType(textParamDef));
    }
}