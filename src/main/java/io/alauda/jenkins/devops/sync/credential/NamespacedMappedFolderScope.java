package io.alauda.jenkins.devops.sync.credential;

import com.cloudbees.hudson.plugins.folder.AbstractFolder;
import hudson.Extension;
import hudson.model.Item;
import hudson.model.ItemGroup;
import io.alauda.jenkins.devops.sync.AlaudaFolderProperty;
import io.alauda.jenkins.plugins.credentials.metadata.CredentialsWithMetadata;
import io.alauda.jenkins.plugins.credentials.metadata.NamespaceProvider;
import io.alauda.jenkins.plugins.credentials.scope.KubernetesSecretScope;

@Extension
public class NamespacedMappedFolderScope implements KubernetesSecretScope {
  @Override
  public boolean isInScope(ItemGroup itemGroup) {
    return findParentAlaudaFolder(itemGroup) != null;
  }

  @Override
  public boolean shouldShowInScope(
      ItemGroup itemGroup, CredentialsWithMetadata credentialsWithMetadata) {
    AbstractFolder<?> folder = findParentAlaudaFolder(itemGroup);
    if (folder == null) {
      return false;
    }

    return folder
        .getName()
        .equals(credentialsWithMetadata.getMetadata(NamespaceProvider.NAMESPACE_METADATA));
  }

  private AbstractFolder<?> findParentAlaudaFolder(ItemGroup itemGroup) {
    while (itemGroup != null) {
      if (itemGroup instanceof AbstractFolder) {
        AbstractFolder<?> folder = (AbstractFolder) itemGroup;

        AlaudaFolderProperty property = folder.getProperties().get(AlaudaFolderProperty.class);
        if (property != null) {
          return folder;
        }
      }

      if (itemGroup instanceof Item) {
        itemGroup = ((Item) itemGroup).getParent();
      } else {
        break;
      }
    }
    return null;
  }
}
