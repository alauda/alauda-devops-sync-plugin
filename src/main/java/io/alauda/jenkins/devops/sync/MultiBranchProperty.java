package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import com.cloudbees.hudson.plugins.folder.AbstractFolderProperty;
import com.cloudbees.hudson.plugins.folder.AbstractFolderPropertyDescriptor;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import jenkins.branch.MultiBranchProject;
import org.kohsuke.stapler.DataBoundConstructor;

public class MultiBranchProperty extends AbstractFolderProperty<AbstractFolder<?>>
        implements AlaudaJobProperty {
    private String uid;
    private String namespace;
    private String name;
    private String resourceVersion;

    @DataBoundConstructor
    public MultiBranchProperty(String namespace, String name,
                               String uid, String resourceVersion) {
        this.namespace = namespace;
        this.name = name;
        this.uid = uid;
        this.resourceVersion = resourceVersion;
    }

    @Extension
    public static class DescriptorImpl extends AbstractFolderPropertyDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Alauda MultiBranch Project";
        }

        @Override
        public boolean isApplicable(Class<? extends AbstractFolder> containerType) {
            return MultiBranchProject.class.isAssignableFrom(containerType);
        }
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
}
