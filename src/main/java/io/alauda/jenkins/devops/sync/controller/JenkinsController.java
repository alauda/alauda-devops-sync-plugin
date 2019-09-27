package io.alauda.jenkins.devops.sync.controller;

import hudson.Extension;
import hudson.ExtensionList;
import hudson.PluginManager;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.UpdateSite;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1BindingCondition;
import io.alauda.devops.java.client.models.V1alpha1Jenkins;
import io.alauda.devops.java.client.models.V1alpha1JenkinsList;
import io.alauda.devops.java.client.models.V1alpha1JenkinsStatus;
import io.alauda.devops.java.client.utils.DeepCopyUtils;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;
import io.alauda.jenkins.devops.sync.ConnectionAliveDetectTask;
import io.alauda.jenkins.devops.sync.client.JenkinsClient;
import io.alauda.jenkins.devops.sync.constants.Constants;
import io.kubernetes.client.JSON;
import io.kubernetes.client.extended.controller.Controller;
import io.kubernetes.client.extended.controller.builder.ControllerBuilder;
import io.kubernetes.client.extended.controller.builder.ControllerManagerBuilder;
import io.kubernetes.client.extended.controller.reconciler.Reconciler;
import io.kubernetes.client.extended.controller.reconciler.Request;
import io.kubernetes.client.extended.controller.reconciler.Result;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import io.kubernetes.client.informer.cache.Lister;
import jenkins.model.Jenkins;
import jenkins.plugins.linkedjobs.actions.LabelDashboardAction;
import jenkins.plugins.linkedjobs.model.LabelAtomData;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Extension
public class JenkinsController implements ResourceSyncController, ConnectionAliveDetectTask.HeartbeatResourceDetector {
    private static final Logger logger = LoggerFactory.getLogger(JenkinsController.class);
    private static final String CONTROLLER_NAME = "JenkinsController";

    private LocalDateTime lastEventComingTime;

    @Override
    public void add(ControllerManagerBuilder managerBuilder, SharedInformerFactory factory) {
        DevopsAlaudaIoV1alpha1Api api = new DevopsAlaudaIoV1alpha1Api();

        SharedIndexInformer<V1alpha1Jenkins> informer = factory.getExistingSharedIndexInformer(V1alpha1Jenkins.class);
        if (informer == null) {
            informer = factory.sharedIndexInformerFor(params ->
                    api.listJenkinsCall(null,
                            null,
                            null,
                            null,
                            null,
                            null,
                            params.resourceVersion,
                            params.timeoutSeconds,
                            params.watch,
                            null,
                            null), V1alpha1Jenkins.class, V1alpha1JenkinsList.class, TimeUnit.MINUTES.toMillis(5));
        }

        Controller controller = ControllerBuilder.defaultBuilder(factory)
                .watch(workQueue -> ControllerBuilder.controllerWatchBuilder(V1alpha1Jenkins.class, workQueue)
                        .withWorkQueueKeyFunc(jenkins -> new Request(jenkins.getMetadata().getName()))
                        .withOnUpdateFilter((oldJenkins, newJenkins) -> {
                            if (!oldJenkins.getMetadata().getResourceVersion().equals(newJenkins.getMetadata().getResourceVersion())) {
                                lastEventComingTime = LocalDateTime.now();
                            }
                            return true;
                        })
                        .build())
                .withWorkerCount(1)
                .withName(CONTROLLER_NAME)
                .withReconciler(new JenkinsReconciler(new Lister<>(informer.getIndexer())))
                .build();

        managerBuilder.addController(controller);
    }

    @Override
    public LocalDateTime lastEventComingTime() {
        return lastEventComingTime;
    }

    @Override
    public String resourceName() {
        return "Jenkins";
    }

    private class JenkinsReconciler implements Reconciler {

        private Lister<V1alpha1Jenkins> lister;

        public JenkinsReconciler(Lister<V1alpha1Jenkins> lister) {
            this.lister = lister;
        }

        @Override
        public Result reconcile(Request request) {
            String jenkinsName = request.getName();
            String configuredJenkinsServiceName = AlaudaSyncGlobalConfiguration.get().getJenkinsService();
            if (!jenkinsName.equals(configuredJenkinsServiceName)) {
                logger.debug("[{}] Jenkins '{}' not matched the configured Jenkins Service '{}', will skip it", CONTROLLER_NAME, jenkinsName, configuredJenkinsServiceName);
                return new Result(false);
            }

            V1alpha1Jenkins jenkins = lister.get(jenkinsName);
            if (jenkins == null) {
                logger.error("[{}] Jenkins '{}' has been deleted, will stop syncing resource", CONTROLLER_NAME, jenkinsName);
                ResourceSyncManager.getSyncManager().restart();
                return new Result(false);
            }

            V1alpha1Jenkins jenkinsCopy = DeepCopyUtils.deepCopy(jenkins);

            logger.debug("[{}] Add labels status to Jenkins '{}'", CONTROLLER_NAME, jenkinsCopy);
            addLabelsCondition(jenkinsCopy);
            logger.debug("[{}] Add plugins status to Jenkins '{}'", CONTROLLER_NAME, jenkinsCopy);
            addPluginsCondition(jenkinsCopy);
            logger.debug("[{}] Add warnings status to Jenkins '{}'", CONTROLLER_NAME, jenkinsCopy);
            addWarningsCondition(jenkinsCopy);

            boolean succeed = JenkinsClient.getInstance().updateJenkins(jenkins, jenkinsCopy);

            if (!succeed) {
                new Result(true);
            }
            return new Result(false);
        }

        private void addLabelsCondition(V1alpha1Jenkins jenkins) {
            logger.debug("Starting find all active labels from Jenkins");
            ExtensionList<LabelDashboardAction> labelActions = ExtensionList.lookup(LabelDashboardAction.class);

            List<String> labels = new LinkedList<>();
            if (labelActions.size() == 0) {
                logger.warn("Cannot to get labels from Label Linked Jobs plugin, reason: unable to load LabelDashboardAction");
            } else {
                LabelDashboardAction labelDashboardAction = labelActions.get(0);
                labelDashboardAction.getRefresh();

                labels.addAll(labelDashboardAction
                        .getLabelsData()
                        .stream()
                        .map(LabelAtomData::getLabel)
                        .collect(Collectors.toList()));
            }

            labels.addAll(Jenkins.getInstance()
                    .getLabels()
                    .stream()
                    .flatMap(label -> label.listAtoms().stream())
                    .map(Label::getName)
                    .collect(Collectors.toList()));

            labels = labels.stream()
                    .distinct()
                    .collect(Collectors.toList());

            labels = removeUnavailableLabels(labels);

            logger.debug("Found {} labels", labels.size());

            NodeList nodeList = new NodeList();
            nodeList.setLabels(labels);
            addJenkinsStatusCondition(jenkins, new JSON().serialize(nodeList), Constants.JENKINS_NODES_CONDITION);
        }

        private List<String> removeUnavailableLabels(List<String> labels) {
            List<Node> nodes = Jenkins.getInstance()
                    .getNodes();
            // Jenkins.getInstance().getNodes() will return all nodes except Jenkins itself
            nodes.add(Jenkins.getInstance());

            List<String> unavailableFromStaticNodes = nodes
                    .stream()
                    .filter(node -> node.getNumExecutors() <= 0)
                    .flatMap(node -> Label.parse(node.getLabelString())
                            .stream()
                            .map(Label::getName))
                    .collect(Collectors.toList());

            logger.debug("labels {}", unavailableFromStaticNodes);

            return labels.stream()
                    .filter(label -> !unavailableFromStaticNodes.contains(label))
                    .collect(Collectors.toList());
        }

        private void addPluginsCondition(V1alpha1Jenkins jenkins) {
            logger.debug("Starting to list plugin status");
            List<PluginStatus> pluginStatusList = getPluginsStatus();
            logger.debug("Found {} plugins", pluginStatusList.size());

            PluginList pluginList = new PluginList();
            pluginList.setPlugins(pluginStatusList);
            addJenkinsStatusCondition(jenkins, new JSON().serialize(pluginList), Constants.JENKINS_PLUGINS_CONDITION);
        }

        private void addJenkinsStatusCondition(V1alpha1Jenkins jenkins, String status, String conditionName) {
            V1alpha1JenkinsStatus jenkinsStatus = jenkins.getStatus();
            if (jenkinsStatus == null) {
                jenkins.setStatus(new V1alpha1JenkinsStatus());
            }

            List<V1alpha1BindingCondition> conditions = jenkins.getStatus()
                    .getConditions();
            if (conditions == null) {
                conditions = new LinkedList<>();
                jenkins.getStatus().setConditions(conditions);
            }

            conditions.removeIf(condition -> condition.getType().equals(Constants.JENKINS_CONDITION_STATUS_TYPE) && condition.getName().equals(conditionName));

            Optional<V1alpha1BindingCondition> conditionOptional = conditions.stream()
                    .filter(c -> conditionName.equals(c.getName()))
                    .findAny();

            V1alpha1BindingCondition condition = null;
            if (conditionOptional.isPresent()) {
                condition = conditionOptional.get();
            } else {
                condition = new V1alpha1BindingCondition();
                condition.setName(conditionName);
                condition.setType(Constants.JENKINS_CONDITION_STATUS_TYPE);
                conditions.add(condition);
            }

            condition.setMessage(status);
            condition.setLastAttempt(new DateTime());
        }


        private void addWarningsCondition(V1alpha1Jenkins jenkins) {
            //TODO add warnings to Jenkins condition
        }

        private List<PluginStatus> getPluginsStatus() {
            PluginManager pluginManager = Jenkins.getInstance().getPluginManager();
            List<PluginStatus> pluginStatusList = new LinkedList<>();

            // add failed plugins' status to condition
            pluginManager.getFailedPlugins().forEach(failedPlugin -> {
                PluginStatus pluginStatus = new PluginStatus();
                pluginStatus.setName(failedPlugin.name);
                pluginStatus.setDescription(failedPlugin.cause.getMessage());
                pluginStatus.setStatus(Constants.JENKINS_PLUGIN_STATUS_FAILED);

                pluginStatusList.add(pluginStatus);
            });

            pluginManager.getPlugins().forEach(plugin -> {
                PluginStatus pluginStatus = new PluginStatus();
                pluginStatus.setName(plugin.getLongName());

                UpdateSite.Plugin pluginInfo = plugin.getInfo();
                if (pluginInfo != null) {
                    pluginStatus.setDescription(pluginInfo.excerpt);
                }

                pluginStatus.setVersion(plugin.getVersion());
                pluginStatus.setStatus(plugin.isActive() ? Constants.JENKINS_PLUGIN_STATUS_ACTIVE : Constants.JENKINS_PLUGIN_STATUS_INACTIVE);
                pluginStatus.setUpdatable(plugin.hasUpdate());

                pluginStatusList.add(pluginStatus);
            });
            return pluginStatusList;
        }
    }

    private static class PluginList {
        private List<PluginStatus> plugins;

        public List<PluginStatus> getPlugins() {
            return plugins;
        }

        public void setPlugins(List<PluginStatus> plugins) {
            this.plugins = plugins;
        }
    }

    private static class PluginStatus {
        private String name;
        private String version;
        private String description;
        private String status;
        private boolean updatable;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public boolean isUpdatable() {
            return updatable;
        }

        public void setUpdatable(boolean updatable) {
            this.updatable = updatable;
        }
    }

    private static class NodeList {
        private List<String> labels;

        public List<String> getLabels() {
            return labels;
        }

        public void setLabels(List<String> labels) {
            this.labels = labels;
        }
    }

}
