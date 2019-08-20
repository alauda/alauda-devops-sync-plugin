/*
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
import hudson.BulkChange;
import hudson.model.Item;
import hudson.model.ItemGroup;
import hudson.util.XStream2;
import io.alauda.devops.java.client.models.*;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.constants.Annotations;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.alauda.jenkins.devops.sync.icons.AlaudaFolderIcon;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.filters.StringInputStream;
import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.ISODateTimeFormat;

import java.io.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import static io.alauda.jenkins.devops.sync.constants.Constants.FOLDER_DESCRIPTION;
import static io.alauda.jenkins.devops.sync.constants.PipelinePhases.*;
import static java.util.logging.Level.FINE;

public abstract class AlaudaUtils {
    private final static Logger logger = Logger.getLogger(AlaudaUtils.class.getName());
    private static final String PLUGIN_NAME = "alauda-sync";

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

    private static final DateTimeFormatter dateFormatter = ISODateTimeFormat.dateTimeNoMillis();


    public static boolean isPipelineStrategyPipeline(V1alpha1Pipeline pipeline) {
        if (pipeline.getSpec() == null) {
            logger.warning("bad input, null spec: " + pipeline);
            return false;
        }

        V1alpha1PipelineStrategy strategy = pipeline.getSpec().getStrategy();
        if (strategy == null) {
            logger.warning("bad input, null strategy: " + pipeline);
            return false;
        }

        V1alpha1PipelineStrategyJenkins jenkins = strategy.getJenkins();

        return (jenkins != null && (
            StringUtils.isNotEmpty(jenkins.getJenkinsfile()) ||
            StringUtils.isNotEmpty(jenkins.getJenkinsfilePath())
          )
        );
    }

    /**
     * Checks if a {@link V1alpha1PipelineConfig} relates to a Jenkins build
     *
     * @param pc
     *            the PipelineConfig
     * @return true if this is an Alauda DevOps PipelineConfig which should be mirrored
     *         to a Jenkins Job
     */
    public static boolean isPipelineStrategyPipelineConfig(V1alpha1PipelineConfig pc) {
        if(pc == null) {
            return false;
        }
        V1alpha1PipelineStrategy strategy = pc.getSpec().getStrategy();
        if(strategy == null) {
            return false;
        }

        V1alpha1PipelineStrategyJenkins jenkins = strategy.getJenkins();
        if(jenkins == null) {
            return false;
        }

        return (
                StringUtils.isNotEmpty(jenkins.getJenkinsfile())
                        || StringUtils.isNotEmpty(jenkins.getJenkinsfilePath())
        );
    }


    /**
     * Finds the Jenkins job name for the given {@link V1alpha1PipelineConfig}.
     *
     * @param pc the PipelineConfig
     * @return the jenkins job name for the given BuildConfig
     */
    public static String jenkinsJobName(V1alpha1PipelineConfig pc) {
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
     *            the name of the {@link V1alpha1PipelineConfig} in in the namespace
     * @return the jenkins job name for the given namespace and name
     */
    public static String jenkinsJobName(String namespace, String pipelineConfigName) {
        return namespace + "-" + pipelineConfigName;
    }

    /**
     * Finds the full jenkins job path including folders for the given
     * {@link V1alpha1PipelineConfig}.
     *
     * @param pc
     *            the PipelineConfig
     * @return the jenkins job name for the given PipelineConfig
     */
    public static String jenkinsJobFullName(V1alpha1PipelineConfig pc) {
        String jobName = pc.getMetadata().getAnnotations().get(Annotations.JENKINS_JOB_PATH);

        if (StringUtils.isNotBlank(jobName)) {
            return jobName;
        }

        return pc.getMetadata().getNamespace() + "/" + jenkinsJobName(pc);
    }

    /**
     * Returns the parent for the given item full name or default to the active
     * jenkins if it does not exist
     * @param activeJenkins activeJenkins
     * @param fullName fullName
     * @param namespace namespace
     * @return item
     */
    public static ItemGroup  getOrCreateFullNameParent(Jenkins activeJenkins, String fullName, String namespace)
            throws IOException {
        int idx = fullName.lastIndexOf('/');
        if (idx > 0) {
            String parentFullName = fullName.substring(0, idx);
            Item parent = activeJenkins.getItemByFullName(parentFullName);
            if (parent instanceof Folder) {
                Folder folder = ((Folder) parent);
                AlaudaFolderProperty alaPro = folder.getProperties().get(AlaudaFolderProperty.class);
                if(alaPro == null) {
                    folder.addProperty(new AlaudaFolderProperty());
                } else {
                    alaPro.setDirty(false);
                }

                folder.setIcon(new AlaudaFolderIcon());
                folder.save();

                return folder;
            } else if (parent == null && parentFullName.equals(namespace)) {
                Folder folder = new Folder(activeJenkins, namespace);
                folder.setDescription(FOLDER_DESCRIPTION + namespace);
                folder.addProperty(new AlaudaFolderProperty());
                folder.setIcon(new AlaudaFolderIcon());
                BulkChange bk = new BulkChange(folder);
                InputStream jobStream = new StringInputStream(new XStream2().toXML(folder));

                activeJenkins.createProjectFromXML(namespace, jobStream).save();
                bk.commit();

                // lets look it up again to be sure
                parent = activeJenkins.getItemByFullName(namespace);
                if (parent instanceof ItemGroup) {
                    return (ItemGroup) parent;
                }
            } else {
                throw new IllegalArgumentException(String.format("cannot create folder %s", parentFullName));
            }
        }
        return activeJenkins;
    }

    /**
     * Finds the Jenkins job display name for the given {@link V1alpha1PipelineConfig}.
     *
     * @param pc the PipelineConfig
     * @return the jenkins job display name for the given PipelineConfig
     */
    public static String jenkinsJobDisplayName(V1alpha1PipelineConfig pc) {
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
     *            the name of the {@link V1alpha1PipelineConfig} in in the namespace
     * @return the jenkins job display name for the given namespace and name
     */
    public static String jenkinsJobDisplayName(String namespace, String pipelineConfigName) {
        return namespace + "/" + pipelineConfigName;
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
    public static void updateGitSourceUrl(V1alpha1PipelineConfig pipelineConfig, String gitUrl, String ref) {
        V1alpha1PipelineSource source = getOrCreatePipelineSource(pipelineConfig);
        V1alpha1PipelineSourceGit git = source.getGit();
        if (git == null) {
            git = new V1alpha1PipelineSourceGit();
            source.setGit(git);
        }
        git.setUri(gitUrl);
        git.setRef(ref);
    }

    public static void updateSvnSourceUrl(V1alpha1PipelineConfig pipelineConfig, String svnUrl) {
        V1alpha1PipelineSource source = getOrCreatePipelineSource(pipelineConfig);
        V1alpha1PipelineSourceSvn svn = source.getSvn();
        if (svn == null) {
            svn = new V1alpha1PipelineSourceSvn();
            source.setSvn(svn);
        }
        svn.setUri(svnUrl);
    }

    public static V1alpha1PipelineSource getOrCreatePipelineSource(V1alpha1PipelineConfig pipelineConfig) {
        V1alpha1PipelineConfigSpec spec = pipelineConfig.getSpec();
        if (spec == null) {
            spec = new V1alpha1PipelineConfigSpec();
            pipelineConfig.setSpec(spec);
        }
        V1alpha1PipelineSource source = spec.getSource();
        if (source == null) {
            source = new V1alpha1PipelineSource();
            spec.setSource(source);
        }
        return source;
    }

    public static boolean isValidSource(V1alpha1PipelineSource source) {
        return isValidGitSource(source) || isValidSvnSource(source);
    }

    public static boolean isValidGitSource(V1alpha1PipelineSource source) {
        return source != null && source.getGit() != null && source.getGit().getUri() != null;
    }

    public static boolean isValidSvnSource(V1alpha1PipelineSource source) {
        return source != null && source.getSvn() != null && source.getSvn().getUri() != null;
    }

    public static void updatePipelinePhase(V1alpha1Pipeline pipeline, String phase) {
        logger.log(FINE, "setting pipeline to {0} in namespace {1}/{2}", new Object[]{phase, pipeline.getMetadata().getNamespace(), pipeline.getMetadata().getName()});

        V1alpha1Pipeline oldPipeline = DeepCopyUtils.deepCopy(pipeline);

        V1alpha1PipelineStatus stats = pipeline.getStatus();
        if (stats == null) {
            stats = new V1alpha1PipelineStatusBuilder().build();
        }
        stats.setPhase(phase);
        pipeline.setStatus(stats);

        Clients.get(V1alpha1Pipeline.class).update(oldPipeline, pipeline);
        pipeline.setStatus(stats);
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

    public static String formatTimestamp(long timestamp) {
        return dateFormatter.print(new DateTime(timestamp));
    }

    public static String getCurrentTimestamp() {
      return dateFormatter.print(new DateTime());
    }

    public static long parseTimestamp(String timestamp) {
        return dateFormatter.parseMillis(timestamp);
    }

    public static boolean isCancellable(V1alpha1PipelineStatus pipelineStatus) {
        String phase = pipelineStatus.getPhase();
        return phase.equals(QUEUED) || phase.equals(PENDING)
                || phase.equals(RUNNING);
    }

    public static boolean isNew(V1alpha1PipelineStatus pipelineStatus) {
        return pipelineStatus.getPhase().equals(PENDING);
    }

    public static boolean isCancelled(V1alpha1PipelineStatus status) {
      return status != null && status.isAborted();
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


    public static boolean isBindingToCurrentJenkins(V1alpha1JenkinsBinding jenkinsBinding) {
        AlaudaSyncGlobalConfiguration pluginConfig = AlaudaSyncGlobalConfiguration.get();

        String jenkinsName = jenkinsBinding.getSpec().getJenkins().getName();
        String jenkinsService = pluginConfig.getJenkinsService();

        return (jenkinsName.equals(jenkinsService));
    }
}
