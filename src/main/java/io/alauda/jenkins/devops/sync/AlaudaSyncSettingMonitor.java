package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;
import hudson.util.HttpResponses;
import io.alauda.jenkins.devops.sync.controller.ResourceControllerManager;
import java.io.IOException;
import jenkins.model.Jenkins;
import org.apache.commons.lang3.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;
import org.kohsuke.stapler.interceptor.RequirePOST;

@Extension
@Symbol("alaudaSyncStatus")
public class AlaudaSyncSettingMonitor extends AdministrativeMonitor {
  public static final String ID = "alaudaSyncStatus";
  private String message;

  static AlaudaSyncSettingMonitor get(Jenkins j) {
    return (AlaudaSyncSettingMonitor) j.getAdministrativeMonitor(ID);
  }

  public AlaudaSyncSettingMonitor() {
    super(ID);
  }

  @Override
  public String getDisplayName() {
    return "Alauda DevOps Sync plugin status";
  }

  @Override
  public boolean isActivated() {
    boolean isStarted = ResourceControllerManager.getControllerManager().isStarted();

    message = ResourceControllerManager.getControllerManager().getManagerStatus();
    if (!isStarted && StringUtils.isEmpty(message)) {
      message = "Resource Sync Manger has not started yet";
    }

    return !isStarted || !StringUtils.isEmpty(message);
  }

  @RequirePOST
  public HttpResponse doAct(StaplerRequest req, StaplerResponse rsp) throws IOException {
    if (req.hasParameter("no")) {
      disable(true);
      return HttpResponses.redirectViaContextPath("/manage");
    } else {
      return HttpResponses.redirectViaContextPath("/configure");
    }
  }

  public String getMessage() {
    return message;
  }
}
