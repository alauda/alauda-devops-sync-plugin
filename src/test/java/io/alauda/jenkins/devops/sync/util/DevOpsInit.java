package io.alauda.jenkins.devops.sync.util;

import hudson.remoting.Base64;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;

import java.io.IOException;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.devops.client.models.PipelineConfigStatus.PipelineConfigPhaseCreating;
import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_RUN_POLICY_SERIAL;

public class DevOpsInit {
    private final static Logger logger = Logger.getLogger(DevOpsInit.class.getName());

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

    public DevOpsInit init() throws InterruptedException {
        AlaudaDevOpsClient client = getClient();

        namespace = createProject(client).getMetadata().getName();
        jenkinsName = createJenkins(client).getMetadata().getName();

        Thread.sleep(2000);

        secretName = createSecret(client).getMetadata().getName();
        bindingName = createBinding(client).getMetadata().getName();

        return this;
    }

    /**
     * Has a simple echo script
     * @param client k8s client
     * @return PipelineConfig instance
     */
    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client) {
        return createPipelineConfig(client, "echo '1'");
    }

    public void deletePipelineConfig(AlaudaDevOpsClient client, String name) {
        client.pipelineConfigs().inNamespace(namespace).withName(name).delete();
    }

    public void addCronTrigger4PipelineConfig(AlaudaDevOpsClient client, String name, String cron) {
        client.pipelineConfigs().inNamespace(namespace)
                .withName(name).edit()
                .editOrNewSpec().addNewTrigger().withType("cron").withNewCron()
                .withEnabled(true).withRule(cron).endCron()
                .endTrigger().endSpec().done();
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client, String script) {
        return createPipelineConfig(client, script, null);
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client, String script, String cron) {
        return createPipelineConfig(client, script, null, cron);
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client, String script, String jenkinsFilePath, String cron) {
        return createPipelineConfig(client, script, jenkinsFilePath, cron, null);
    }

    public PipelineConfig createPipelineConfig(AlaudaDevOpsClient client, String script, String jenkinsFilePath, String cron, String secret) {
        PipelineConfigSpecBuilder specBuilder = new PipelineConfigSpecBuilder()
//                .withNewSource().withNewSecret().endSecret().endSource()
                .withNewStrategy()
                .withNewJenkins().withJenkinsfile(script).withJenkinsfilePath(jenkinsFilePath).endJenkins()
                .endStrategy()
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy(PIPELINE_RUN_POLICY_SERIAL);

        if(secret != null) {
            specBuilder.withNewSource()
                    .withNewSecret().withName(secret).endSecret()
                    .withNewGit("http://github.com", "master")
                    .endSource();
        }

        if(cron != null) {
            PipelineTrigger trigger = new PipelineTriggerBuilder()
                    .withType("cron").withNewCron().withEnabled(true).withRule(cron).endCron().build();
            specBuilder.withTriggers(trigger);
        }

        return client.pipelineConfigs()
                .createNew()
                .withNewMetadata()
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .withGenerateName("pipeline-config-")
                .withNamespace(namespace).endMetadata()
                .withSpec(specBuilder.build())
                .done();
    }

    public PipelineConfig updatePipelineConfigWithParams(AlaudaDevOpsClient client,
                                                         String name,
                                                         Map<String, String> paramMap,
                                                         String script) {
        List<PipelineParameter> params = convertTo(paramMap);

        PipelineStrategy strategy = new PipelineStrategyBuilder()
                .withNewJenkins().withJenkinsfile(script).endJenkins().build();

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
                .withRunPolicy(PIPELINE_RUN_POLICY_SERIAL)
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
        List<PipelineParameter> pipelineParameters = new ArrayList<>();

        params.forEach(p -> {
            PipelineParameter pipParam = new PipelineParameterBuilder().withType(p.getType())
                    .withName(p.getName())
                    .withValue(paramMap.get(p.getName()))
                    .withDescription(p.getDescription()).build();
            pipelineParameters.add(pipParam);
        });

        return client.pipelines().createNew()
                .withNewMetadata()
                .withNamespace(namespace)
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .endMetadata()
                .withNewSpec()
                .withParameters(pipelineParameters)
                .withNewPipelineConfig(configName)
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy(PIPELINE_RUN_POLICY_SERIAL)
                .withNewStrategy().withNewJenkins().withJenkinsfile("a").withJenkinsfilePath("a").endJenkins().endStrategy()
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
                .withGenerateName("pipeline-test-")
                .withNamespace(namespace)
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .endMetadata()
                .withNewSpec()
                .withNewPipelineConfig(pipelineConfig)
                .withNewJenkinsBinding(bindingName)
                .withRunPolicy(pipCfgSpec.getRunPolicy())
                .withNewStrategy().withNewJenkins().withJenkinsfile(jenkins.getJenkinsfile()).withJenkinsfilePath(jenkins.getJenkinsfilePath()).endJenkins().endStrategy()
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
                .withNewHttp("http://abc-"+System.nanoTime()+".com")
                .build();

        return client.jenkins().createNew().withNewMetadata()
                .withGenerateName("jenkins-")
                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
                .endMetadata()
                .withSpec(jenkinsSpec)
                .done();
    }

    public Secret createSecret(AlaudaDevOpsClient client) {
        Map<String, String> data = new HashMap<>();
        data.put("username", Base64.encode("a".getBytes()));
        data.put("password", Base64.encode("a".getBytes()));

        return client.secrets()
                .createNew()
                .withData(data)
                .withType(Constants.ALAUDA_DEVOPS_SECRETS_TYPE_BASICAUTH)
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

    public ServiceAccount createServiceAccounts(AlaudaDevOpsClient client) {
        return client.serviceAccounts().createNew()
                .withNewMetadata().withNamespace(namespace)
                .withGenerateName("serviceaccounttest").endMetadata()
                .done();
    }

    public Pod createPod(AlaudaDevOpsClient client, final String accountName) {
        return client.pods().createNew()
                .withNewMetadata().withNamespace(namespace).withGenerateName("pod-test-").endMetadata()
                .withNewSpec().withServiceAccountName(accountName)
                .addNewContainer().withName("as").withImage("WatcherAliveCheck").endContainer()
                .endSpec()
                .done();
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
        final String formatTxt = "yyyy-MM-dd'T'HH:mm:ss'Z'";
        final SimpleDateFormat format = new SimpleDateFormat(formatTxt);
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
                    expireTime.setTime(TimeUtils.getUTCTime());
                    expireTime.add(Calendar.HOUR_OF_DAY, -2);

                    expire = format.parse(createTime).after(expireTime.getTime());
                } catch (ParseException e) {
                    logger.log(Level.SEVERE, String.format("wrong time format: %s", formatTxt), e);
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
}
