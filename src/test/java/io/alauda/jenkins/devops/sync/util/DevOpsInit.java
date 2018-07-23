package io.alauda.jenkins.devops.sync.util;

import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.PipelinePhases;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;

import java.io.Closeable;
import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.ZoneId;
import java.util.*;

import static io.alauda.devops.client.models.PipelineConfigStatus.PipelineConfigPhaseCreating;

public class DevOpsInit implements Closeable {
    public static final String TEST_FLAG = "alauda.test";
    public static final String TEST_FLAG_VALUE = "true";

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

        namespace = createProject(client).getMetadata().getName();
        jenkinsName = createJenkins(client).getMetadata().getName();
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        secretName = createSecret(client).getMetadata().getName();
        bindingName = createBinding(client).getMetadata().getName();

        return this;
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client) {
        return createPipelineConfig(client, "echo '1'");
    }

    public void deletePipelineConfig(AlaudaDevOpsClient client, String name) {
        client.pipelineConfigs().inNamespace(namespace).withName(name).delete();
    }

    public void addCronTrigger4PipelineConfig(AlaudaDevOpsClient client, String name, String cron) {
        client.pipelineConfigs().inNamespace(namespace)
                .withName(name).edit()
                .editOrNewSpec().addNewTrigger().withType("cron").withNewCron(true, cron)
                .endTrigger().endSpec().done();
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client, String script) {
        return client.pipelineConfigs()
                .createNew()
                .withNewMetadata()
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .withGenerateName("pipeline-config-")
                .withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewSource().withNewSecret().endSecret().endSource()
                .withNewStrategy()
                .withNewJenkins().withJenkinsfile(script).endJenkins()
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
                .withNewJenkins(script, "").build();

        return client.pipelineConfigs().inNamespace(namespace).withName(name)
                .edit()
                .editSpec()
                .withStrategy(strategy)
                .withParameters(params).endSpec()
                .withNewStatus().withPhase(PipelineConfigPhaseCreating).endStatus()
                .done();
    }

    public PipelineConfig createPipelineConfigWithParams(AlaudaDevOpsClient client,
                                                         Map<String, String> paramMap,
                                                         String script) {
        List<PipelineParameter> params = convertTo(paramMap);

        return client.pipelineConfigs()
                .createNew()
                .withNewMetadata()
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
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
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
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

    public Pipeline getPipeline(AlaudaDevOpsClient client, String name) {
        return client.pipelines().inNamespace(namespace).withName(name).get();
    }

    public void abortPipeline(AlaudaDevOpsClient client, String pipelineName) {
        Pipeline pipeline = client.pipelines().inNamespace(namespace).withName(pipelineName).get();
        PipelineStatus status = pipeline.getStatus();
        status.setAborted(true);

        client.pipelines().inNamespace(namespace).withName(pipelineName)
                .edit().withStatus(status).done();
    }

    public Pipeline createPipeline(AlaudaDevOpsClient client, String pipelineConfig) {
        PipelineConfig pipCfg = client.pipelineConfigs()
                .inNamespace(namespace)
                .withName(pipelineConfig).get();

        PipelineConfigSpec pipCfgSpec = pipCfg.getSpec();
        PipelineStrategyJenkins jenkins = pipCfgSpec.getStrategy().getJenkins();

        return client.pipelines().createNew()
                .withNewMetadata()
                .withNamespace(namespace)
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .endMetadata()
                .withNewSpec()
                .withNewPipelineConfig(pipelineConfig)
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy(pipCfgSpec.getRunPolicy())
                .withNewStrategy().withNewJenkins(jenkins.getJenkinsfile(), jenkins.getJenkinsfilePath()).endStrategy()
                .endSpec()
                .done();
    }

    public JenkinsBinding createBinding(AlaudaDevOpsClient client) {
        return client.jenkinsBindings()
                .createNew()
                .withNewMetadata()
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
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
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .endMetadata()
                .withSpec(jenkinsSpec)
                .done();
    }

    public Secret createSecret(AlaudaDevOpsClient client) {
        return client.secrets()
                .createNew()
                .withNewMetadata()
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .withNamespace(namespace)
                .withGenerateName("secret-test-")
                .endMetadata()
                .done();
    }

    public Project createProject(AlaudaDevOpsClient client) {
        DoneableProject project = client.projects().createNew()
                .withNewMetadata()
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
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

        cleanExpireResources();
    }

    private void cleanExpireResources() {
        final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'");
        final ProjectList projects = getClient().projects().list();
        if(projects != null && projects.getItems() != null) {
            List<Project> items = projects.getItems();
            for(Project project : items) {
                if(!hasTestLabel(project)) {
                    continue;
                }

                String createTime = project.getMetadata().getCreationTimestamp();
                boolean expire = false;

                try {
                    Calendar expireTime = Calendar.getInstance();
                    expireTime.setTime(getUTCTime());
                    expireTime.add(Calendar.HOUR_OF_DAY, -2);

                    expire = format.parse(createTime).after(expireTime.getTime());
                } catch (ParseException e) {
                    e.printStackTrace();
                }

                if(expire) {
                    getClient().projects().delete(project);
                }
            }
        }
    }

    private boolean hasTestLabel(Project project) {
        Map<String, String> labels = project.getMetadata().getLabels();
        if(labels == null) {
            return false;
        }

        return TEST_FLAG_VALUE.equals(labels.get(TEST_FLAG));
    }

    private Date getUTCTime() throws ParseException {
        StringBuilder UTCTimeBuffer = new StringBuilder();
        Calendar cal = Calendar.getInstance() ;
        int zoneOffset = cal.get(java.util.Calendar.ZONE_OFFSET);
        int dstOffset = cal.get(java.util.Calendar.DST_OFFSET);
        cal.add(java.util.Calendar.MILLISECOND, -(zoneOffset + dstOffset));
        int year = cal.get(Calendar.YEAR);
        int month = cal.get(Calendar.MONTH)+1;
        int day = cal.get(Calendar.DAY_OF_MONTH);
        int hour = cal.get(Calendar.HOUR_OF_DAY);
        int minute = cal.get(Calendar.MINUTE);
        UTCTimeBuffer.append(year).append("-").append(month).append("-").append(day) ;
        UTCTimeBuffer.append(" ").append(hour).append(":").append(minute) ;
        return new SimpleDateFormat("yyyy-MM-dd HH:mm").parse(UTCTimeBuffer.toString()) ;
    }
}
