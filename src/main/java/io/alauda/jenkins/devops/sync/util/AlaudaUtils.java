/**
 * Copyright (C) 2018 Alauda.io
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.alauda.jenkins.devops.sync.util;

import com.cloudbees.hudson.plugins.folder.Folder;
import com.fasterxml.jackson.annotation.JsonIgnore;
import hudson.BulkChange;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.util.XStream2;
import io.alauda.devops.client.AlaudaDevOpsClient;
import io.alauda.devops.client.AlaudaDevOpsConfigBuilder;
import io.alauda.devops.client.DefaultAlaudaDevOpsClient;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.icons.AlaudaFolderIcon;
import io.alauda.kubernetes.api.model.*;
import io.alauda.kubernetes.client.Config;
import io.alauda.kubernetes.client.Version;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.FOLDER_DESCRIPTION;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.*;
import static java.util.logging.Level.FINE;

public abstract class AlaudaUtils {
    private final static Logger logger = Logger.getLogger(AlaudaUtils.class.getName());
    private static final String PLUGIN_NAME = "alauda-sync";

    private static AlaudaDevOpsClient alaudaClient;
    private static String jenkinsPodNamespace = null;

    private AlaudaUtils(){}
    
    static {
        jenkinsPodNamespace = System.getProperty(Constants.ALAUDA_PROJECT_ENV_VAR_NAME);
        if (jenkinsPodNamespace != null && jenkinsPodNamespace.trim().length() > 0) {
            jenkinsPodNamespace = jenkinsPodNamespace.trim();
        } else {
            File f = new File(Constants.KUBERNETES_SERVICE_ACCOUNT_NAMESPACE);
            if (f.exists()) {
                try (FileReader fr = new FileReader(Constants.KUBERNETES_SERVICE_ACCOUNT_NAMESPACE);
                     BufferedReader br = new BufferedReader(fr)){
                    // should just be one line
                    jenkinsPodNamespace = br.readLine();
                    if (jenkinsPodNamespace != null && jenkinsPodNamespace.trim().length() > 0) {
                        jenkinsPodNamespace = jenkinsPodNamespace.trim();
                    }

                } catch (FileNotFoundException e) {
                    logger.log(Level.FINE, "getNamespaceFromPodInputs", e);
                } catch (IOException e) {
                    logger.log(Level.FINE, "getNamespaceFromPodInputs", e);
                }
            }
        }
    }

    private static final DateTimeFormatter dateFormatter = ISODateTimeFormat
            .dateTimeNoMillis();

    /**
     * Initializes an {@link AlaudaDevOpsClient}
     *
     * @param serverUrl
     *            the optional URL of where the OpenShift cluster API server is
     *            running
     */
    public synchronized static void initializeAlaudaDevOpsClient(String serverUrl) {
      AlaudaDevOpsConfigBuilder configBuilder = new AlaudaDevOpsConfigBuilder();
      if (serverUrl != null && !serverUrl.isEmpty()) {
          configBuilder.withMasterUrl(serverUrl);
      }

      Config config = configBuilder.build();
      if (config != null) {
        if (Jenkins.getInstance().getPluginManager() != null && Jenkins.getInstance().getPluginManager()
          .getPlugin(PLUGIN_NAME) != null) {
          config.setUserAgent(PLUGIN_NAME + "-plugin-"
            + Jenkins.getInstance().getPluginManager()
            .getPlugin(PLUGIN_NAME).getVersion() + "/alauda-devops-"
            + Version.clientVersion());
        }
        alaudaClient = new DefaultAlaudaDevOpsClient(config);
        logger.info("Alauda client is created well.");
      } else {
          logger.warning("Config builder could not build a configuration for Alauda Connection");
      }
    }

    public synchronized static AlaudaDevOpsClient getAlaudaClient() {
        return alaudaClient;
    }

    // Get the current AlaudaDevOpsClient and configure to use the current Oauth
    // token.
    public synchronized static AlaudaDevOpsClient getAuthenticatedAlaudaClient() {
        if (alaudaClient != null) {
            String token = CredentialsUtils.getCurrentToken();
            if (token != null && token.length() > 0) {
                alaudaClient.getConfiguration().setOauthToken(token);
            }
        }

        return alaudaClient;
    }

    public synchronized static void shutdownAlaudaClient() {
        if (alaudaClient != null) {
            alaudaClient.close();
            alaudaClient = null;
        }
    }

    public static boolean isPipelineStrategyPipeline(Pipeline pipeline) {
        if (pipeline.getSpec() == null) {
            logger.warning("bad input, null spec: " + pipeline);
            return false;
        }

        PipelineStrategy strategy = pipeline.getSpec().getStrategy();
        if (strategy == null) {
            logger.warning("bad input, null strategy: " + pipeline);
            return false;
        }

        PipelineStrategyJenkins jenkins = strategy.getJenkins();

        return (jenkins != null && (
            StringUtils.isNotEmpty(jenkins.getJenkinsfile()) ||
            StringUtils.isNotEmpty(jenkins.getJenkinsfilePath())
          )
        );
    }

    /**
     * Checks if a {@link PipelineConfig} relates to a Jenkins build
     *
     * @param pc
     *            the PipelineConfig
     * @return true if this is an Alauda DevOps PipelineConfig which should be mirrored
     *         to a Jenkins Job
     */
    public static boolean isPipelineStrategyPipelineConfig(PipelineConfig pc) {
        if(pc == null) {
            return false;
        }
        PipelineStrategy strategy = pc.getSpec().getStrategy();
        if(strategy == null) {
            return false;
        }

        PipelineStrategyJenkins jenkins = strategy.getJenkins();
        if(jenkins == null) {
            return false;
        }

        return (
                StringUtils.isNotEmpty(jenkins.getJenkinsfile())
                        || StringUtils.isNotEmpty(jenkins.getJenkinsfilePath())
        );
    }


    /**
     * Finds the Jenkins job name for the given {@link PipelineConfig}.
     *
     * @param pc the PipelineConfig
     * @return the jenkins job name for the given BuildConfig
     */
    public static String jenkinsJobName(PipelineConfig pc) {
        String namespace = pc.getMetadata().getNamespace();
        String name = pc.getMetadata().getName();
        return jenkinsJobName(namespace, name);
    }

    /**
     * Creates the Jenkins Job name for the given pipelineConfigName
     *
     * @param namespace
     *            the namespace of the build
     * @param pipelineConfigName
     *            the name of the {@link PipelineConfig} in in the namespace
     * @return the jenkins job name for the given namespace and name
     */
    public static String jenkinsJobName(String namespace, String pipelineConfigName) {
        return namespace + "-" + pipelineConfigName;
    }

    /**
     * Finds the full jenkins job path including folders for the given
     * {@link PipelineConfig}.
     *
     * @param pc
     *            the PipelineConfig
     * @return the jenkins job name for the given PipelineConfig
     */
    public static String jenkinsJobFullName(PipelineConfig pc) {
        String jobName = getAnnotation(pc, Annotations.JENKINS_JOB_PATH);
        if (StringUtils.isNotBlank(jobName)) {
            return jobName;
        }
        return getNamespace(pc) + "/" + getName(pc);
    }

    /**
     * Returns the parent for the given item full name or default to the active
     * jenkins if it does not exist
     * @param activeJenkins activeJenkins
     * @param fullName fullName
     * @param namespace namespace
     * @return item
     */
    public static ItemGroup getFullNameParent(Jenkins activeJenkins,
            String fullName, String namespace) {
        int idx = fullName.lastIndexOf('/');
        if (idx > 0) {
            String parentFullName = fullName.substring(0, idx);
            Item parent = activeJenkins.getItemByFullName(parentFullName);
            if (parent instanceof Folder) {
                Folder folder = ((Folder) parent);
                AlaudaFolderProperty alaPro = folder.getProperties().get(AlaudaFolderProperty.class);
                if(alaPro == null) {
                    try {
                        folder.addProperty(new AlaudaFolderProperty());
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else {
                    alaPro.setDirty(false);
                }

                try {
                    folder.setIcon(new AlaudaFolderIcon());
                    folder.save();
                } catch (IOException e) {
                    e.printStackTrace();
                }

                return folder;
            } else if (parentFullName.equals(namespace)) {

                // lets lazily create a new folder for this namespace parent
                Folder folder = new Folder(activeJenkins, namespace);
                try {
                    folder.setDescription(FOLDER_DESCRIPTION + namespace);
                    folder.addProperty(new AlaudaFolderProperty());
                    folder.setIcon(new AlaudaFolderIcon());
                } catch (IOException e) {
                    // ignore
                }
                BulkChange bk = new BulkChange(folder);
                InputStream jobStream = new StringInputStream(
                        new XStream2().toXML(folder));
                try {
                    activeJenkins.createProjectFromXML(namespace, jobStream)
                            .save();
                } catch (IOException e) {
                    logger.warning("Failed to create the Folder: " + namespace);
                }
                try {
                    bk.commit();
                } catch (IOException e) {
                    logger.warning("Failed to commit toe BulkChange for the Folder: "
                            + namespace);
                }
                // lets look it up again to be sure
                parent = activeJenkins.getItemByFullName(namespace);
                if (parent instanceof ItemGroup) {
                    return (ItemGroup) parent;
                }
            }
        }
        return activeJenkins;
    }

  /**
   * Finds the Jenkins job display name for the given {@link PipelineConfig}.
   *
   * @param pc
   *            the PipelineConfig
   * @return the jenkins job display name for the given PipelineConfig
   */
  public static String jenkinsJobDisplayName(PipelineConfig pc) {
    String namespace = pc.getMetadata().getNamespace();
    String name = pc.getMetadata().getName();
    return jenkinsJobDisplayName(namespace, name);
  }

    /**
     * Creates the Jenkins Job display name for the given pipelineConfigName
     *
     * @param namespace
     *            the namespace of the build
     * @param pipelineConfigName
     *            the name of the {@link PipelineConfig} in in the namespace
     * @return the jenkins job display name for the given namespace and name
     */
    public static String jenkinsJobDisplayName(String namespace, String pipelineConfigName) {
        return namespace + "/" + pipelineConfigName;
    }

    /**
     * Gets the current namespace running Jenkins inside or returns a reasonable
     * default
     *
     * @param configuredJenkinsService
     *            the optional configured jenkins service
     * @param client
     *            the AlaudaDevOps client
     * @return the default namespace using either the configuration value, the
     *         default namespace on the client or "default"
     */
    public static String[] getNamespaceOrUseDefault(
            String configuredJenkinsService, AlaudaDevOpsClient client) {
        List<String> namespaces = new ArrayList<>();
        if (configuredJenkinsService == null || configuredJenkinsService.isEmpty()) {
          logger.fine("No jenkins service configured... will look using jenkins host...");
          // fetch jenkins host address
          String jenkinsHost = getJenkinsURL(client, null);
          // if we have a configured address we can look
          // for AlaudaDevops Jenkins instances to make a comparisson
          if (jenkinsHost != null && !jenkinsHost.isEmpty()) {
            // fetch Jenkins services
            // compare addresses
            JenkinsList jenkinsList = client.jenkins().list();
            if (jenkinsList.getItems() != null && jenkinsList.getItems().size() > 0) {
              for (io.alauda.kubernetes.api.model.Jenkins jen : jenkinsList.getItems()) {
                if (jenkinsHost.equals(jen.getSpec().getHttp().getHost())) {
                  configuredJenkinsService = jen.getMetadata().getName();
                  logger.fine("Found correct jenkins service: "+ jen.getMetadata().getName());
                  break;
                }
              }
            }
          } else {
            logger.warning("Could not get a jenkins host address for search... Please setup the Jenkins host"+
            " address or adjust settings to the given Jenkins Service in Alauda DevOps");
          }
        }

        if (configuredJenkinsService != null && !configuredJenkinsService.isEmpty()) {
          logger.fine("Looking for bindings for the jenkins instance "+configuredJenkinsService+"...");
          // look for all the jenkinsbindings
          // comparing the name and fetch bound namespaces
          JenkinsBindingList jenkinsBindingList = client.jenkinsBindings().inAnyNamespace().list();
          if (jenkinsBindingList != null && jenkinsBindingList.getItems() != null && jenkinsBindingList.getItems().size() > 0) {
            for (JenkinsBinding binding : jenkinsBindingList.getItems()) {
              if (configuredJenkinsService.equals(binding.getSpec().getJenkins().getName())) {
                // found the jenkins binding
                String namespace = binding.getMetadata().getNamespace();
                if (!namespaces.contains(namespace)) {
                  namespaces.add(namespace);
                }
              }
            }
          }
        } else {
          logger.warning("Jenkins service name was not set. Please set the name of the service in the Jenkins configuration."+
          " It must be the same as in the Alauda DevOps.");
        }

      return namespaces.toArray(new String[]{});
    }


    /**
     * Returns the public URL of the given service
     *
     * @param alaudaClient
     *            the AlaudaDevOpsClient to use
     * @param defaultProtocolText
     *            the protocol text part of a URL such as <code>http://</code>
     * @param namespace
     *            the Kubernetes namespace
     * @param serviceName
     *            the service name
     * @return the external URL of the service
     */
    public static String getExternalServiceUrl(AlaudaDevOpsClient alaudaClient,
            String defaultProtocolText, String namespace, String serviceName) {
//        if (namespace != null && serviceName != null) {
//            try {
//                RouteList routes = alaudaClient.routes()
//                        .inNamespace(namespace).list();
//                for (Route route : routes.getItems()) {
//                    RouteSpec spec = route.getSpec();
//                    if (spec != null
//                            && spec.getTo() != null
//                            && "Service".equalsIgnoreCase(spec.getTo()
//                                    .getKind())
//                            && serviceName.equalsIgnoreCase(spec.getTo()
//                                    .getName())) {
//                        String host = spec.getHost();
//                        if (host != null && host.length() > 0) {
//                            if (spec.getTls() != null) {
//                                return "https://" + host;
//                            }
//                            return "http://" + host;
//                        }
//                    }
//                }
//            } catch (Exception e) {
//                logger.log(Level.WARNING, "Could not find Route for service "
//                        + namespace + "/" + serviceName + ". " + e, e);
//            }
//            // lets try the portalIP instead
//            try {
//                Service service = alaudaClient.services()
//                        .inNamespace(namespace).withName(serviceName).get();
//                if (service != null) {
//                    ServiceSpec spec = service.getSpec();
//                    if (spec != null) {
//                        String host = spec.getClusterIP();
//                        if (host != null && host.length() > 0) {
//                            return defaultProtocolText + host;
//                        }
//                    }
//                }
//            } catch (Exception e) {
//                logger.log(Level.WARNING, "Could not find Route for service "
//                        + namespace + "/" + serviceName + ". " + e, e);
//            }
//        }

        // lets default to the service DNS name
        return defaultProtocolText + serviceName;
    }

    /**
     * Calculates the external URL to access Jenkins
     *
     * @param namespace
     *            the namespace Jenkins is runing inside
     * @param alaudaDevOpsClient
     *            the AlaudaDevops client
     * @return the external URL to access Jenkins
     */
    public static String getJenkinsURL(AlaudaDevOpsClient alaudaDevOpsClient,
            String namespace) {
        // if the user has explicitly configured the jenkins root URL, use it
        String rootUrl = null;
        // TODO: ??
//        try {
//          rootUrl = Jenkins.getInstance().getRootUrl();
//        } catch (Exception exc) {
//          logger.severe("Exception when getting jenkins Root URL: "+exc);
//        }
//        if (StringUtils.isNotEmpty(rootUrl)) {
//            return rootUrl;
//        }
        return rootUrl;

        // otherwise, we'll see if we are running in a pod and can infer it from
        // the service/route
        // TODO we will eventually make the service name configurable, with the
        // default of "jenkins"
//        return getExternalServiceUrl(alaudaDevOpsClient, "http://", namespace,
//                "jenkins");
    }

    public static String getNamespacefromPodInputs() {
        return jenkinsPodNamespace;
    }


    /**
     * Lazily creates the PipelineConfigSource if need be then updates the git URL
     *
     * @param pipelineConfig the PipelineConfig to update
     * @param gitUrl         the URL to the git repo
     * @param ref            the git ref (commit/branch/etc) for the build
     */
    public static void updateGitSourceUrl(PipelineConfig pipelineConfig, String gitUrl, String ref) {
        PipelineConfigSpec spec = pipelineConfig.getSpec();
        if (spec == null) {
            spec = new PipelineConfigSpec();
            pipelineConfig.setSpec(spec);
        }
        PipelineSource source = spec.getSource();
        if (source == null) {
            source = new PipelineSource();
            spec.setSource(source);
        }
        PipelineSourceGit git = source.getGit();
        if (git == null) {
            git = new PipelineSourceGit();
            source.setGit(git);
        }
        git.setUri(gitUrl);
        git.setRef(ref);
    }

    public static void updatePipelinePhase(Pipeline pipeline, String phase) {
        logger.log(FINE, "setting pipeline to {0} in namespace {1}/{2}", new Object[]{phase, pipeline.getMetadata().getNamespace(), pipeline.getMetadata().getName()});
        AlaudaDevOpsClient client = getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.severe("Can't found alauda client.");
            return;
        }

        String namespace = pipeline.getMetadata().getNamespace();
        String name = pipeline.getMetadata().getName();
        Pipeline pipe = client.pipelines().inNamespace(namespace).withName(name).get();
        if (pipe == null) {
            logger.warning(() -> "Can't find Pipeline by namespace: " + namespace + ", name: " + name);
            return;
        }

        PipelineStatus stats = pipe.getStatus();
        if (stats == null) {
            stats = new PipelineStatusBuilder().build();
        }
        stats.setPhase(phase);
        pipe.setStatus(stats);

        client.pipelines()
                .inNamespace(namespace)
                .withName(name)
                .patch(pipe);
    }

    /**
     * Maps a Jenkins Job name to an ObjectShift BuildConfig name
     *
     * @return the namespaced name for the BuildConfig
     * @param jobName
     *            the job to associate to a BuildConfig name
     * @param namespace
     *            the default namespace that Jenkins is running inside
     */
    public static NamespaceName buildConfigNameFromJenkinsJobName(
            String jobName, String namespace) {
        // TODO lets detect the namespace separator in the jobName for cases
        // where a jenkins is used for
        // BuildConfigs in multiple namespaces?
        return new NamespaceName(namespace, jobName);
    }

    /**
     * Maps a Jenkins Job name to an PipelineConfig name
     *
     * @param jobName   the job to associate to a PipelineConfig name
     * @param namespace the default namespace that Jenkins is running inside
     * @return the namespaced name for the PipelineConfig
     */
    public static NamespaceName pipelineConfigNameFromJenkinsJobName(String jobName, String namespace) {
        return new NamespaceName(namespace, jobName);
    }

    public static long parseResourceVersion(HasMetadata obj) {
        return parseResourceVersion(obj.getMetadata().getResourceVersion());
    }

    public static long parseResourceVersion(String resourceVersion) {
        try {
            return Long.parseLong(resourceVersion);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    public static String formatTimestamp(long timestamp) {
        return dateFormatter.print(new DateTime(timestamp));
    }

    public static String getCurrentTimestamp() {
      return dateFormatter.print(new DateTime());
    }

    public static long parseTimestamp(String timestamp) {
        return dateFormatter.parseMillis(timestamp);
    }

    public static boolean isCancellable(PipelineStatus pipelineStatus) {
        String phase = pipelineStatus.getPhase();
        return phase.equals(QUEUED) || phase.equals(PENDING)
                || phase.equals(RUNNING);
    }

    public static boolean isNew(PipelineStatus pipelineStatus) {
        return pipelineStatus.getPhase().equals(PENDING);
    }

    public static boolean isCancelled(PipelineStatus status) {
      return status != null && status.getAborted();
    }

    /**
     * Lets convert the string to btw a valid kubernetes resource name
     */
    public static String convertNameToValidResourceName(String text) {
        String lower = text.toLowerCase();
        StringBuilder builder = new StringBuilder();
        boolean started = false;
        char lastCh = ' ';
        for (int i = 0, last = lower.length() - 1; i <= last; i++) {
            char ch = lower.charAt(i);
            if (!(ch >= 'a' && ch <= 'z') && !(ch >= '0' && ch <= '9')) {
                if (ch == '/') {
                    ch = '.';
                } else if (ch != '.' && ch != '-') {
                    ch = '-';
                }
                if (!started || lastCh == '-' || lastCh == '.' || i == last) {
                    continue;
                }
            }
            builder.append(ch);
            started = true;
            lastCh = ch;
        }
        return builder.toString();
    }

    public static String getAnnotation(HasMetadata resource, String name) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            Map<String, String> annotations = metadata.getAnnotations();
            if (annotations != null) {
                return annotations.get(name);
            }
        }
        return null;
    }

    public static void addAnnotation(HasMetadata resource, String name,
            String value) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata == null) {
            metadata = new ObjectMeta();
            resource.setMetadata(metadata);
        }
        Map<String, String> annotations = metadata.getAnnotations();
        if (annotations == null) {
            annotations = new HashMap<>();
            metadata.setAnnotations(annotations);
        }
        annotations.put(name, value);
    }

    public static String getNamespace(HasMetadata resource) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            return metadata.getNamespace();
        }
        return null;
    }

    public static String getName(HasMetadata resource) {
        ObjectMeta metadata = resource.getMetadata();
        if (metadata != null) {
            return metadata.getName();
        }
        return null;
    }

    public static boolean isBindingToCurrentJenkins(JenkinsBinding jenkinsBinding) {
        AlaudaSyncGlobalConfiguration pluginConfig = AlaudaSyncGlobalConfiguration.get();

        String jenkinsName = jenkinsBinding.getSpec().getJenkins().getName();
        String jenkinsService = pluginConfig.getJenkinsService();

        return (jenkinsName.equals(jenkinsService));
    }

    public static boolean isBindingToCurrentJenkins(String namespace) {
        AlaudaDevOpsClient client = AlaudaUtils.getAuthenticatedAlaudaClient();
        if(client == null) {
            logger.severe("Can't found alauda client.");
            return false;
        }

        String jenkinsService = AlaudaSyncGlobalConfiguration.get().getJenkinsService();

        JenkinsBindingList jenkinsBindings = client.jenkinsBindings().inNamespace(namespace).list();
        if(jenkinsBindings != null) {
            for(JenkinsBinding binding : jenkinsBindings.getItems()) {
                if(binding.getSpec().getJenkins().getName().equals(jenkinsService)) {
                    return true;
                }
            }
        }

        return false;
    }

    abstract class StatelessReplicationControllerMixIn extends
            ReplicationController {
        @JsonIgnore
        private ReplicationControllerStatus status;

        StatelessReplicationControllerMixIn() {
        }

        @JsonIgnore
        public abstract ReplicationControllerStatus getStatus();
    }

    abstract class ObjectMetaMixIn extends ObjectMeta {
        @JsonIgnore
        private String creationTimestamp;
        @JsonIgnore
        private String deletionTimestamp;
        @JsonIgnore
        private Long generation;
        @JsonIgnore
        private String resourceVersion;
        @JsonIgnore
        private String selfLink;
        @JsonIgnore
        private String uid;

        ObjectMetaMixIn() {
        }

        @JsonIgnore
        public abstract String getCreationTimestamp();

        @JsonIgnore
        public abstract String getDeletionTimestamp();

        @JsonIgnore
        public abstract Long getGeneration();

        @JsonIgnore
        public abstract String getResourceVersion();

        @JsonIgnore
        public abstract String getSelfLink();

        @JsonIgnore
        public abstract String getUid();
    }
}
