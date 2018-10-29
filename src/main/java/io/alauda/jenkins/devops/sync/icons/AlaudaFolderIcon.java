package io.alauda.jenkins.devops.sync.icons;

import com.cloudbees.hudson.plugins.folder.FolderIcon;
import com.cloudbees.hudson.plugins.folder.FolderIconDescriptor;
import hudson.Extension;
import hudson.model.Hudson;
import org.kohsuke.stapler.Stapler;

public class AlaudaFolderIcon extends FolderIcon {
    @Override
    public String getImageOf(String size) {
        String image = iconClassNameImageOf(size);
        return image != null
                ? image
                : (Stapler.getCurrentRequest().getContextPath() + Hudson.RESOURCE_PATH
                + "/plugin/alauda-devops-sync/images/"+size+"/alauda.png");
    }

    @Override
    public String getDescription() {
        return "Alauda Folder";
    }

    @Extension(ordinal=100)
    public static class DescriptorImpl extends FolderIconDescriptor {
        @Override
        public String getDisplayName() {
            return "Alauda Folder";
        }
    }
}
