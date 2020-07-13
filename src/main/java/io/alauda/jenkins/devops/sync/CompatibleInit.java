package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.security.ACL;
import hudson.security.ACLContext;
import io.alauda.jenkins.devops.sync.function.AlaudaMultiBranchPipelineSCMAdapter;
import io.alauda.jenkins.devops.sync.function.AlaudaPipelineFilter;
import io.alauda.jenkins.devops.sync.function.AlaudaPipelineSCMAdapter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.DoNotUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CompatibleInit {
  private static final Logger logger = LoggerFactory.getLogger(CompatibleInit.class);

  @Initializer(after = InitMilestone.JOB_LOADED)
  @SuppressWarnings("unused")
  @Restricted(DoNotUse.class)
  public static void scmBehaviorInitial() {
    Jenkins instance = Jenkins.get();
    File flagFile = new File(instance.getRootDir(), "alauda-devops-sync-init.yaml");
    if (flagFile.isFile()) {
      logger.info("alauda devops sync plugin was initialized");
      return;
    }

    try (ACLContext ignore = ACL.as(ACL.SYSTEM)) {
      scmInit(instance);
    }

    try (FileOutputStream out = new FileOutputStream(flagFile)) {
      out.write("".getBytes());
    } catch (IOException e) {
      logger.error("cannot set the compatible init flag", e);
    }
  }

  private static void scmInit(Jenkins instance) {
    List<Folder> folders = instance.getItems(Folder.class);

    // init scm for pipeline
    folders.forEach(
        folder ->
            folder
                .getItems()
                .stream()
                .filter(new AlaudaPipelineFilter())
                .forEach(new AlaudaPipelineSCMAdapter()));

    // init scm for multi-branch pipeline
    folders.forEach(
        folder -> {
          folder
              .getItems()
              .stream()
              .filter(item -> item instanceof WorkflowMultiBranchProject)
              .forEach(new AlaudaMultiBranchPipelineSCMAdapter());
        });
  }
}
