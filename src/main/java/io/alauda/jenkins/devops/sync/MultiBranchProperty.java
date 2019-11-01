package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import hudson.Extension;
import hudson.model.Descriptor;
import javax.annotation.Nonnull;
import jenkins.branch.MultiBranchProject;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.StaplerRequest;

public class MultiBranchProperty extends AbstractFolderProperty<AbstractFolder<?>>
    implements AlaudaJobProperty {
  private String uid;
  private String namespace;
  private String name;
  private String resourceVersion;
  private String contextAnnotation;

  @DataBoundConstructor
  public MultiBranchProperty(String namespace, String name, String uid, String resourceVersion) {
    this.namespace = namespace;
    this.name = name;
    this.uid = uid;
    this.resourceVersion = resourceVersion;
  }

  @Override
  public AbstractFolderProperty<?> reconfigure(StaplerRequest req, JSONObject form)
      throws Descriptor.FormException {
    return this;
  }

  @Override
  public String getUid() {
    return this.uid;
  }

  @Override
  public void setUid(String uid) {
    this.uid = uid;
  }

  @Override
  public String getName() {
    return this.name;
  }

  @Override
  public void setName(String name) {
    this.name = name;
  }

  @Override
  public String getNamespace() {
    return this.namespace;
  }

  @Override
  public void setNamespace(String namespace) {
    this.namespace = namespace;
  }

  @Override
  public String getResourceVersion() {
    return this.resourceVersion;
  }

  @Override
  public void setResourceVersion(String resourceVersion) {
    this.resourceVersion = resourceVersion;
  }

  @Override
  public String getContextAnnotation() {
    return contextAnnotation;
  }

  @Override
  public void setContextAnnotation(String contextAnnotation) {
    this.contextAnnotation = contextAnnotation;
  }

  @Extension
  public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {

    @Nonnull
    @Override
    public String getDisplayName() {
      return "Alauda MultiBranch Project";
    }

    @Override
    public boolean isApplicable(Class<? extends AbstractFolder> containerType) {
      return MultiBranchProject.class.isAssignableFrom(containerType);
    }
  }
}
