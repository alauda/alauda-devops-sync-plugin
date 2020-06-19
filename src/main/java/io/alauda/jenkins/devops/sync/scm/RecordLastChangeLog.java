package io.alauda.jenkins.devops.sync.scm;

import hudson.EnvVars;
import hudson.Extension;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitSCM;
import hudson.plugins.git.Revision;
import hudson.plugins.git.extensions.GitSCMExtension;
import hudson.plugins.git.extensions.GitSCMExtensionDescriptor;
import java.io.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.Nonnull;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.DataBoundConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RecordLastChangeLog extends GitSCMExtension {
  public static final Logger logger = LoggerFactory.getLogger(RecordLastChangeLog.class);

  private static final String GIT_COMMIT_HASH = "GIT_COMMIT_HASH";
  private static final String GIT_COMMIT_AUTHOR = "GIT_COMMIT_AUTHOR";
  private static final String GIT_COMMIT_AUTHOR_EMAIL = "GIT_COMMIT_AUTHOR_EMAIL";
  private static final String GIT_COMMIT_MESSAGE = "GIT_COMMIT_MESSAGE";

  @DataBoundConstructor
  public RecordLastChangeLog() {}

  @Override
  public Revision decorateRevisionToBuild(
      GitSCM scm,
      Run<?, ?> build,
      GitClient git,
      TaskListener listener,
      Revision marked,
      Revision rev)
      throws IOException, InterruptedException, GitException {
    LastChangeData lastChangeData = build.getAction(LastChangeData.class);
    if (lastChangeData == null) {
      lastChangeData = new LastChangeData();
      build.addAction(lastChangeData);
    }

    ByteArrayOutputStream data = new ByteArrayOutputStream();
    git.changelog().includes(rev.getSha1()).max(1).to(new PrintWriter(data)).execute();

    StringBuilder msgBuf = new StringBuilder();
    InputStreamReader reader = new InputStreamReader(new ByteArrayInputStream(data.toByteArray()));
    BufferedReader rea = new BufferedReader(reader);
    String line = null;
    while ((line = rea.readLine()) != null) {
      if (line.startsWith("author ")) {
        List<String> authorData = new ArrayList<>(Arrays.asList(line.split(" ")));
        if (authorData.size() >= 6) {
          authorData.remove(authorData.size() - 1);
          authorData.remove(authorData.size() - 1);
          authorData.remove(authorData.size() - 1);
          authorData.remove(0);

          lastChangeData.setAuthorEmail(authorData.get(authorData.size() - 1));
          authorData.remove(authorData.size() - 1);
          lastChangeData.setAuthor(String.join(" ", authorData));
        }
      } else if (line.startsWith("commit ")) {
        lastChangeData.setCommit(line.split(" ")[1]);
      } else if (line.startsWith("tree ")
          || line.startsWith("parent ")
          || line.startsWith("committer ")
          || line.startsWith(":100644 ")
          || line.startsWith(":000000 ")) {
      } else {
        msgBuf.append(line);
      }
    }
    lastChangeData.setMessage(msgBuf.toString().trim());

    logger.debug(data.toString());
    return super.decorateRevisionToBuild(scm, build, git, listener, marked, rev);
  }

  @Override
  public void onCheckoutCompleted(GitSCM scm, Run<?, ?> build, GitClient git, TaskListener listener)
      throws IOException, InterruptedException, GitException {
    LastChangeData lastChangeData = build.getAction(LastChangeData.class);
    if (lastChangeData != null) {
      EnvVars environment = build.getEnvironment(listener);
      environment.put(GIT_COMMIT_HASH, lastChangeData.getCommit());
      environment.put(GIT_COMMIT_AUTHOR, lastChangeData.getAuthor());
      environment.put(GIT_COMMIT_AUTHOR_EMAIL, lastChangeData.getAuthorEmail());
      environment.put(GIT_COMMIT_MESSAGE, lastChangeData.getMessage());
    }

    super.onCheckoutCompleted(scm, build, git, listener);
  }

  @Extension
  public static class DescriptorImpl extends GitSCMExtensionDescriptor {
    /** {@inheritDoc} */
    @Override
    @Nonnull
    public String getDisplayName() {
      return "Record the last change log";
    }
  }
}
