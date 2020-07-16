package io.alauda.jenkins.devops.sync.scm;

import hudson.Extension;
import jenkins.plugins.git.traits.GitSCMExtensionTrait;
import jenkins.plugins.git.traits.GitSCMExtensionTraitDescriptor;
import org.kohsuke.stapler.DataBoundConstructor;

public class RecordLastChangeLogTrait extends GitSCMExtensionTrait<RecordLastChangeLog> {
  @DataBoundConstructor
  public RecordLastChangeLogTrait() {
    super(new RecordLastChangeLog());
  }

  /** Our {@link hudson.model.Descriptor} */
  @Extension
  public static class DescriptorImpl extends GitSCMExtensionTraitDescriptor {
    /** {@inheritDoc} */
    @Override
    public String getDisplayName() {
      return "Record the last changeLog";
    }
  }

  @Override
  public boolean equals(Object o) {
    // there's not any fields in this Object
    return true;
  }
}
