package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.StaplerRequest;

public class AlaudaFolderProperty extends AbstractFolderProperty<AbstractFolder<?>> {
    private boolean dirty;

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

    private static class AlaudaFolderPropertyDescriptor extends AbstractFolderPropertyDescriptor {
        @Override
        public AbstractFolderProperty<?> newInstance(StaplerRequest req, JSONObject formData) throws FormException {
            return (AlaudaFolderProperty) super.newInstance(req, formData);
        }
    }
}
