package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;

public class AlaudaFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {
    private boolean dirty;

    @DataBoundConstructor
    public AlaudaFolderProperty(boolean dirty) {
        this.dirty = dirty;
    }

    public boolean isDirty() {
        return dirty;
    }
}
