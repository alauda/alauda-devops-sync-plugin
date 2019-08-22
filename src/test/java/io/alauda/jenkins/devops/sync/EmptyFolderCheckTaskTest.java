package io.alauda.jenkins.devops.sync;

import com.cloudbees.hudson.plugins.folder.Folder;
import hudson.util.DescribableList;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBinding;
import io.alauda.devops.java.client.models.V1alpha1JenkinsBindingSpecBuilder;
import io.alauda.jenkins.devops.sync.client.Clients;
import io.alauda.jenkins.devops.sync.client.JenkinsBindingClient;
import io.alauda.jenkins.devops.sync.client.NamespaceClient;
import io.kubernetes.client.informer.cache.Lister;
import io.kubernetes.client.models.V1Namespace;
import io.kubernetes.client.models.V1ObjectMeta;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.Assert.*;
import static org.powermock.api.mockito.PowerMockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest({Jenkins.class, AlaudaSyncGlobalConfiguration.class, WorkflowJob.class, WorkflowMultiBranchProject.class})
public class EmptyFolderCheckTaskTest {

    private List<Folder> mockFolders = new LinkedList<>();

    @SuppressWarnings({"unchecked"})
    @Before
    public void prepare() {
        mockFolders.clear();

        V1alpha1JenkinsBinding jenkinsBinding = new V1alpha1JenkinsBinding().metadata(
                new V1ObjectMeta().name("test-binding").namespace("test1"));
        jenkinsBinding.setSpec(new V1alpha1JenkinsBindingSpecBuilder()
                .editOrNewJenkins()
                .withNewName("jenkins")
                .endJenkins()
                .build());


        JenkinsBindingClient jenkinsBindingClient = mock(JenkinsBindingClient.class);
        Lister jenkinsBindingLister = mock(Lister.class);

        when(jenkinsBindingClient.lister()).thenReturn(jenkinsBindingLister);
        when(jenkinsBindingLister.list()).thenReturn(Collections.singletonList(jenkinsBinding));

        Clients.register(V1alpha1JenkinsBinding.class, jenkinsBindingClient);

        V1Namespace namespace1 = new V1Namespace().metadata(new V1ObjectMeta().name("test1"));
        V1Namespace namespace2 = new V1Namespace().metadata(new V1ObjectMeta().name("test2"));

        NamespaceClient namespaceClient = mock(NamespaceClient.class);
        Lister namespaceLister = mock(Lister.class);

        when(namespaceClient.lister()).thenReturn(namespaceLister);
        when(namespaceLister.list()).thenReturn(Arrays.asList(namespace1, namespace2));

        Clients.register(V1Namespace.class, namespaceClient);

        mockStatic(Jenkins.class);
        Jenkins jenkins = mock(Jenkins.class);

        when(Jenkins.getInstance()).thenReturn(jenkins);
        when(jenkins.getItems(Folder.class)).thenReturn(mockFolders);

        mockStatic(AlaudaSyncGlobalConfiguration.class);

        AlaudaSyncGlobalConfiguration configuration = mock(AlaudaSyncGlobalConfiguration.class);
        when(AlaudaSyncGlobalConfiguration.get()).thenReturn(configuration);
        when(configuration.getJenkinsService()).thenReturn("jenkins");
    }

    @SuppressWarnings({"unchecked", "deprecation"})
    @Test
    public void testNoPropertiesFolder() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        when(folder.getProperties()).thenReturn(new DescribableList(null));
        when(folder.getName()).thenReturn("test1");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 0);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testAlaudaFolderPropertyNotDirty() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("test1");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 0);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testAlaudaFolderPropertyIsDirty() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(true);

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("test1");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 1);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testNoMatchedNamespace() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("aaaaa");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 1);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testNoMatchedJenkinsBindingInNamespace() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("test2");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 1);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testNoMatchedJenkinsBindingByJenkinsService() {
        AlaudaSyncGlobalConfiguration configuration = mock(AlaudaSyncGlobalConfiguration.class);
        when(AlaudaSyncGlobalConfiguration.get()).thenReturn(configuration);
        when(configuration.getJenkinsService()).thenReturn("jenkins1");

        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("test1");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 1);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testHasMatchedJenkinsBindingInNamespace() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("test1");

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 0);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testHasUserDefinedWorkflowJob() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        WorkflowJob workflowJob = mock(WorkflowJob.class);
        when(workflowJob.getProperty(WorkflowJobProperty.class)).thenReturn(null);

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("aaaaa");
        when(folder.getItems()).thenReturn(Collections.singletonList(workflowJob));

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 0);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testNoUserDefinedWorkflowJob() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList describableList = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        WorkflowJob workflowJob = mock(WorkflowJob.class);
        when(workflowJob.getProperty(WorkflowJobProperty.class)).thenReturn(mock(WorkflowJobProperty.class));

        when(describableList.stream()).thenReturn(Stream.of(property));

        when(folder.getProperties()).thenReturn(describableList);
        when(folder.getName()).thenReturn("aaaaa");
        when(folder.getItems()).thenReturn(Collections.singletonList(workflowJob));

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 1);
    }

    @SuppressWarnings({"unchecked"})
    @Test
    public void testHasUserDefinedMultiBranchProject() {
        Folder folder = mock(Folder.class);
        mockFolders.add(folder);

        DescribableList folderProperties = mock(DescribableList.class);
        AlaudaFolderProperty property = new AlaudaFolderProperty();
        property.setDirty(false);

        DescribableList branchJobProperties = mock(DescribableList.class);

        WorkflowMultiBranchProject branchProject = mock(WorkflowMultiBranchProject.class);
        when(branchProject.getProperties()).thenReturn(branchJobProperties);
        when(branchJobProperties.get(MultiBranchProperty.class)).thenReturn(null);

        when(folderProperties.stream()).thenReturn(Stream.of(property));
        when(folder.getProperties()).thenReturn(folderProperties);
        when(folder.getName()).thenReturn("aaaaa");
        when(folder.getItems()).thenReturn(Collections.singletonList(branchProject));

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 0);
    }

    @Test
    public void testNullFolders() {
        mockStatic(Jenkins.class);
        Jenkins jenkins = mock(Jenkins.class);

        when(Jenkins.getInstance()).thenReturn(jenkins);
        when(jenkins.getItems(Folder.class)).thenReturn(null);

        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.listFoldersShouldDelete().size(), 0);
    }


    @Test
    public void testGetRecurrencePeriod() {
        EmptyFolderCheckTask folderCheck = new EmptyFolderCheckTask();
        assertEquals(folderCheck.getRecurrencePeriod(), TimeUnit.MINUTES.toMillis(10));
    }
}
