package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import hudson.Extension;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;

import javax.annotation.Nonnull;

public class AlaudaFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {
    private boolean dirty;

    @DataBoundConstructor
    public AlaudaFolderProperty() {
    }

    public boolean isDirty() {
        return dirty;
    }

    @DataBoundSetter
    public void setDirty(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public AbstractFolderPropertyDescriptor getDescriptor() {
        return new AlaudaFolderPropertyDescriptor();
    }

    @Extension
    public static class AlaudaFolderPropertyDescriptor extends AbstractFolderPropertyDescriptor {
        @Nonnull
        @Override
        public String getDisplayName() {
            return "AlaudaFolderProperty";
        }
    }
}
