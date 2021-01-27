package io.alauda.jenkins.devops.sync.scm;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
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
  @SuppressFBWarnings(value = "EQ_ALWAYS_TRUE")
  public boolean equals(Object o) {
    // there's not any fields in this Object, so they're always same
    return true;
  }

  @Override
  public int hashCode() {
    // there's not any fields in this Object, so they're always same
    return 1;
  }
}
