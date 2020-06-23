package io.alauda.jenkins.devops.sync.scm;

import hudson.model.Action;
import java.io.Serializable;
import javax.annotation.CheckForNull;
import org.kohsuke.stapler.export.Exported;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean(defaultVisibility = 999)
public class LastChangeData implements Action, Serializable {
  private String author;
  private String authorEmail;
  private String commit;
  private String message;

  @CheckForNull
  @Override
  public String getIconFileName() {
    return null;
  }

  @CheckForNull
  @Override
  public String getDisplayName() {
    return "LastChange";
  }

  @CheckForNull
  @Override
  public String getUrlName() {
    return "lastChange";
  }

  @Exported
  public String getAuthor() {
    return author;
  }

  public void setAuthor(String author) {
    this.author = author;
  }

  @Exported
  public String getAuthorEmail() {
    return authorEmail;
  }

  public void setAuthorEmail(String authorEmail) {
    this.authorEmail = authorEmail;
  }

  @Exported
  public String getCommit() {
    return commit;
  }

  public void setCommit(String commit) {
    this.commit = commit;
  }

  @Exported
  public String getMessage() {
    return message;
  }

  public void setMessage(String message) {
    this.message = message;
  }
}
