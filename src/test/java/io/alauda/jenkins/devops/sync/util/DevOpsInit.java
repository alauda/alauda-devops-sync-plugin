//package io.alauda.jenkins.devops.sync.util;
//
//import hudson.remoting.Base64;
//import io.alauda.devops.client.AlaudaDevOpsClient;
//import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
//import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
//import io.alauda.devops.java.client.models.*;
//import io.alauda.jenkins.devops.sync.constants.Constants;
//import io.alauda.kubernetes.api.model.*;
//import io.alauda.kubernetes.client.Config;
//import io.kubernetes.client.ApiClient;
//import io.kubernetes.client.util.Config;
//
//import java.io.IOException;
//import java.text.ParseException;
//import java.text.SimpleDateFormat;
//import java.util.*;
//import java.util.logging.Level;
//import java.util.logging.Logger;
//
//import static io.alauda.devops.client.models.PipelineConfigStatus.PipelineConfigPhaseCreating;
//import static io.alauda.jenkins.devops.sync.constants.Constants.PIPELINE_RUN_POLICY_SERIAL;
//
//public class DevOpsInit {
//    private final static Logger logger = Logger.getLogger(DevOpsInit.class.getName());
//
//    public static final String TEST_FLAG = "alauda.test";
//    public static final String TEST_FLAG_VALUE = "true";
//
//    private String namespace;
//    private String jenkinsName;
//    private String secretName;
//    private String bindingName;
//
//    public ApiClient getClient() {
//        try {
//            return Config.fromCluster();
//        } catch (IOException e) {
//            throw new RuntimeException(e);
//        }
//    }
//
//    public DevOpsInit init() throws InterruptedException {
//        ApiClient client = getClient();
//
//        namespace = createProject(client).getMetadata().getName();
//        jenkinsName = createJenkins(client).getMetadata().getName();
//
//        Thread.sleep(2000);
//
//        secretName = createSecret(client).getMetadata().getName();
//        bindingName = createBinding(client).getMetadata().getName();
//
//        return this;
//    }
//
//    /**
//     * Has a simple echo script
//     * @param client k8s client
//     * @return PipelineConfig instance
//     */
//    public V1alpha1PipelineConfig createPipelineConfig(ApiClient client) {
//        return createPipelineConfig(client, "echo '1'");
//    }
//
//    public void deletePipelineConfig(ApiClient client, String name) {
//        client.pipelineConfigs().inNamespace(namespace).withName(name).delete();
//    }
//
//    public void addCronTrigger4PipelineConfig(ApiClient client, String name, String cron) {
//        client.pipelineConfigs().inNamespace(namespace)
//                .withName(name).edit()
//                .editOrNewSpec().addNewTrigger().withType("cron").withNewCron()
//                .withEnabled(true).withRule(cron).endCron()
//                .endTrigger().endSpec().done();
//    }
//
//    public V1alpha1PipelineConfig createPipelineConfig(ApiClient client, String script) {
//        return createPipelineConfig(client, script, null);
//    }
//
//    public V1alpha1PipelineConfig createPipelineConfig(ApiClient client, String script, String cron) {
//        return createPipelineConfig(client, script, null, cron);
//    }
//
//    public V1alpha1PipelineConfig createPipelineConfig(ApiClient client, String script, String jenkinsFilePath, String cron) {
//        return createPipelineConfig(client, script, jenkinsFilePath, cron, null);
//    }
//
//    public V1alpha1PipelineConfig createPipelineConfig(ApiClient client, String script, String jenkinsFilePath, String cron, String secret) {
//        V1alpha1PipelineConfigSpecBuilder specBuilder = new V1alpha1PipelineConfigSpecBuilder()
////                .withNewSource().withNewSecret().endSecret().endSource()
//                .withNewStrategy()
//                .withNewJenkins().withJenkinsfile(script).withJenkinsfilePath(jenkinsFilePath).endJenkins()
//                .endStrategy()
//                .withNewJenkinsBinding(bindingName)
//                .withRunPolicy(PIPELINE_RUN_POLICY_SERIAL);
//
//        if(secret != null) {
//            specBuilder.withNewSource()
//                    .withNewSecret().withName(secret).endSecret()
//                    .withNewGit("http://github.com", "master")
//                    .endSource();
//        }
//
//        if(cron != null) {
//            V1alpha1PipelineTrigger trigger = new V1alpha1PipelineTriggerBuilder()
//                    .withType("cron").withNewCron().withEnabled(true).withRule(cron).endCron().build();
//            specBuilder.withTriggers(trigger);
//        }
//
//        return client.pipelineConfigs()
//                .createNew()
//                .withNewMetadata()
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .withGenerateName("pipeline-config-")
//                .withNamespace(namespace).endMetadata()
//                .withSpec(specBuilder.build())
//                .done();
//    }
//
//    public V1alpha1PipelineConfig updatePipelineConfigWithParams(ApiClient client,
//                                                         String name,
//                                                         Map<String, String> paramMap,
//                                                         String script) {
//        List<V1alpha1PipelineParameter> params = convertTo(paramMap);
//
//        V1alpha1PipelineStrategy strategy = new PipelineStrategyBuilder()
//                .withNewJenkins().withJenkinsfile(script).endJenkins().build();
//
//        return client.pipelineConfigs().inNamespace(namespace).withName(name)
//                .edit()
//                .editSpec()
//                .withStrategy(strategy)
//                .withParameters(params).endSpec()
//                .withNewStatus().withPhase(PipelineConfigPhaseCreating).endStatus()
//                .done();
//    }
//
//    public PipelineConfig createPipelineConfigWithParams(ApiClient client,
//                                                         Map<String, String> paramMap,
//                                                         String script) {
//        List<PipelineParameter> params = convertTo(paramMap);
//
//        return client.pipelineConfigs()
//                .createNew()
//                .withNewMetadata()
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .withGenerateName("pipeline-config-")
//                .withNamespace(namespace).endMetadata()
//                .withNewSpec()
//                .withNewStrategy()
//                .withNewJenkins().withJenkinsfile(script).endJenkins()
//                .endStrategy()
//                .withParameters(params)
//                .withNewJenkinsBinding(bindingName)
//                .withRunPolicy(PIPELINE_RUN_POLICY_SERIAL)
//                .endSpec()
//                .done();
//    }
//
//    private List<PipelineParameter> convertTo(Map<String, String> paramMap) {
//        List<PipelineParameter> params = new ArrayList<>();
//        paramMap.forEach((key, val) -> {
//            params.add(new PipelineParameterBuilder().withName(key).withType(val).build());
//        });
//        return params;
//    }
//
//    public void updatePipelineConfig(ApiClient client, String name, String script) {
//        client.pipelineConfigs().inNamespace(namespace)
//                .withName(name)
//                .edit()
//                .editSpec()
//                .editStrategy()
//                .editJenkins()
//                .withJenkinsfile(script)
//                .endJenkins()
//                .endStrategy()
//                .endSpec()
//                .editStatus().withPhase(PipelineConfigPhaseCreating).endStatus()
//                .done();
//    }
//
//    public PipelineConfig getPipelineConfig(ApiClient client, String name) {
//        return client.pipelineConfigs().inNamespace(namespace).withName(name).get();
//    }
//
//    public Pipeline createPipeline(ApiClient client, String configName, Map<String, String> paramMap) {
//        PipelineConfig pipelineConfig = client.pipelineConfigs().inNamespace(namespace).withName(configName).get();
//        List<PipelineParameter> params = pipelineConfig.getSpec().getParameters();
//        List<PipelineParameter> pipelineParameters = new ArrayList<>();
//
//        params.forEach(p -> {
//            PipelineParameter pipParam = new PipelineParameterBuilder().withType(p.getType())
//                    .withName(p.getName())
//                    .withValue(paramMap.get(p.getName()))
//                    .withDescription(p.getDescription()).build();
//            pipelineParameters.add(pipParam);
//        });
//
//        return client.pipelines().createNew()
//                .withNewMetadata()
//                .withNamespace(namespace)
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .endMetadata()
//                .withNewSpec()
//                .withParameters(pipelineParameters)
//                .withNewPipelineConfig(configName)
//                .withNewJenkinsBinding(bindingName)
//                .withRunPolicy(PIPELINE_RUN_POLICY_SERIAL)
//                .withNewStrategy().withNewJenkins().withJenkinsfile("a").withJenkinsfilePath("a").endJenkins().endStrategy()
//                .endSpec()
//                .done();
//    }
//
//    public List<Pipeline> getPipelines(ApiClient client) {
//        return client.pipelines().inNamespace(namespace).list().getItems();
//    }
//
//    public Pipeline getPipeline(ApiClient client, String name) {
//        return client.pipelines().inNamespace(namespace).withName(name).get();
//    }
//
//    public void abortPipeline(ApiClient client, String pipelineName) {
//        Pipeline pipeline = client.pipelines().inNamespace(namespace).withName(pipelineName).get();
//        PipelineStatus status = pipeline.getStatus();
//        status.setAborted(true);
//
//        client.pipelines().inNamespace(namespace).withName(pipelineName)
//                .edit().withStatus(status).done();
//    }
//
//    public Pipeline createPipeline(ApiClient client, String pipelineConfig) {
//        PipelineConfig pipCfg = client.pipelineConfigs()
//                .inNamespace(namespace)
//                .withName(pipelineConfig).get();
//
//        PipelineConfigSpec pipCfgSpec = pipCfg.getSpec();
//        PipelineStrategyJenkins jenkins = pipCfgSpec.getStrategy().getJenkins();
//
//        return client.pipelines().createNew()
//                .withNewMetadata()
//                .withGenerateName("pipeline-test-")
//                .withNamespace(namespace)
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .endMetadata()
//                .withNewSpec()
//                .withNewPipelineConfig(pipelineConfig)
//                .withNewJenkinsBinding(bindingName)
//                .withRunPolicy(pipCfgSpec.getRunPolicy())
//                .withNewStrategy().withNewJenkins().withJenkinsfile(jenkins.getJenkinsfile()).withJenkinsfilePath(jenkins.getJenkinsfilePath()).endJenkins().endStrategy()
//                .endSpec()
//                .done();
//    }
//
//    public JenkinsBinding createBinding(ApiClient client) {
//        return client.jenkinsBindings()
//                .createNew()
//                .withNewMetadata()
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .withGenerateName("jenkins-binding-")
//                .withNamespace(namespace).endMetadata()
//                .withNewSpec()
//                .withNewJenkins().withName(jenkinsName).endJenkins()
////                .withNewAccount()
////                .withNewSecret()
////                .withApiTokenKey("a").withName(secretName)
////                .withUsernameKey("a").endSecret().endAccount()
//                .endSpec()
//                .done();
//    }
//
//    public Jenkins createJenkins(ApiClient client) {
//        String fakeUrl = "http://abc-"+System.nanoTime()+".com";
//        JenkinsSpec jenkinsSpec = new JenkinsSpecBuilder()
//                .withNewHttp(fakeUrl, fakeUrl)
//                .build();
//
//        return client.jenkins().createNew().withNewMetadata()
//                .withGenerateName("jenkins-")
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .endMetadata()
//                .withSpec(jenkinsSpec)
//                .done();
//    }
//
//    public Secret createSecret(ApiClient client) {
//        Map<String, String> data = new HashMap<>();
//        data.put("username", Base64.encode("a".getBytes()));
//        data.put("password", Base64.encode("a".getBytes()));
//
//        return client.secrets()
//                .createNew()
//                .withData(data)
//                .withType(Constants.ALAUDA_DEVOPS_SECRETS_TYPE_BASICAUTH)
//                .withNewMetadata()
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .withNamespace(namespace)
//                .withGenerateName("secret-test-")
//                .endMetadata()
//                .done();
//    }
//
//    public Namespace createProject(ApiClient client) {
//        DoneableNamespace namespace = client.namespaces().createNew()
//                .withNewMetadata()
//                .withLabels(Collections.singletonMap(TEST_FLAG, TEST_FLAG_VALUE))
//                .withGenerateName("namespace-test-")
//                .endMetadata();
//        return namespace.done();
//    }
//
//    public ServiceAccount createServiceAccounts(ApiClient client) {
//        return client.serviceAccounts().createNew()
//                .withNewMetadata().withNamespace(namespace)
//                .withGenerateName("serviceaccounttest").endMetadata()
//                .done();
//    }
//
//    public Pod createPod(ApiClient client, final String accountName) {
//        return client.pods().createNew()
//                .withNewMetadata().withNamespace(namespace).withGenerateName("pod-test-").endMetadata()
//                .withNewSpec().withServiceAccountName(accountName)
//                .addNewContainer().withName("as").withImage("WatcherAliveCheck").endContainer()
//                .endSpec()
//                .done();
//    }
//
//    public String getNamespace() {
//        return namespace;
//    }
//
//    public String getJenkinsName() {
//        return jenkinsName;
//    }
//
//    public String getSecretName() {
//        return secretName;
//    }
//
//    public String getBindingName() {
//        return bindingName;
//    }
//
//    public void close() throws IOException {
//        if(namespace != null) {
//            getClient().namespaces().withName(namespace).delete();
//        }
//
//        if(jenkinsName != null) {
//            getClient().jenkins().withName(jenkinsName).delete();
//        }
//
//        cleanExpireResources();
//    }
//
//    private void cleanExpireResources() {
//        final String formatTxt = "yyyy-MM-dd'T'HH:mm:ss'Z'";
//        final SimpleDateFormat format = new SimpleDateFormat(formatTxt);
//        final NamespaceList projects = getClient().namespaces().list();
//        if(projects != null && projects.getItems() != null) {
//            List<Namespace> items = projects.getItems();
//            for(Namespace project : items) {
//                if(!hasTestLabel(project)) {
//                    continue;
//                }
//
//                String createTime = project.getMetadata().getCreationTimestamp();
//                boolean expire = false;
//
//                try {
//                    Calendar expireTime = Calendar.getInstance();
//                    expireTime.add(Calendar.HOUR_OF_DAY, -2);
//
//                    expire = format.parse(createTime).after(expireTime.getTime());
//                } catch (ParseException e) {
//                    logger.log(Level.SEVERE, String.format("wrong time format: %s", formatTxt), e);
//                }
//
//                if(expire) {
//                    getClient().namespaces().delete(project);
//                }
//            }
//        }
//    }
//
//    private boolean hasTestLabel(Namespace namespace) {
//        Map<String, String> labels = namespace.getMetadata().getLabels();
//        if(labels == null) {
//            return false;
//        }
//
//        return TEST_FLAG_VALUE.equals(labels.get(TEST_FLAG));
//    }
//}
