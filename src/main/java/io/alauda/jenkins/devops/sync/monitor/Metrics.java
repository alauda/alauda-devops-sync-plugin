package io.alauda.jenkins.devops.sync.monitor;

import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import org.jenkinsci.plugins.prometheus.util.ConfigurationUtils;

public class Metrics {
  private Metrics() {}

  public static final Counter completedRequestCounter;

  public static final Counter incomingRequestCounter;

  public static final Gauge remainedRequestsGauge;

  public static final Gauge syncManagerUpGauge;

  static {
    String subsystem = "jenkins";
    String namespace = ConfigurationUtils.getNamespace();

    String[] controllerLabelNames = new String[] {"controller_name"};
    String[] controllerActionLabelNames = new String[] {"controller_name", "action"};

    completedRequestCounter =
        Counter.build()
            .name("sync_controller_reconcile_count")
            .namespace(namespace)
            .subsystem(subsystem)
            .labelNames(controllerLabelNames)
            .help("Completed reconciling count")
            .register();

    incomingRequestCounter =
        Counter.build()
            .name("sync_controller_incoming_request")
            .namespace(namespace)
            .subsystem(subsystem)
            .labelNames(controllerActionLabelNames)
            .help("Incoming request count")
            .register();

    remainedRequestsGauge =
        Gauge.build()
            .name("sync_controller_remained_requests")
            .namespace(namespace)
            .subsystem(subsystem)
            .labelNames(controllerLabelNames)
            .help("Number of remained requests in workqueue")
            .register();

    syncManagerUpGauge =
        Gauge.build()
            .name("sync_plugin_up")
            .namespace(namespace)
            .subsystem(subsystem)
            .help("If the sync plugin ready")
            .register();
  }
}
