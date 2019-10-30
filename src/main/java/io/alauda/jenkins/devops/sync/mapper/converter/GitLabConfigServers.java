package io.alauda.jenkins.devops.sync.mapper.converter;

import hudson.Extension;
import io.alauda.jenkins.devops.sync.constants.CodeRepoServices;
import com.cloudbees.plugins.credentials.CredentialsScope;
import jenkins.model.GlobalConfiguration;

import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.alauda.devops.java.client.models.V1alpha1CodeRepoBinding;
import io.alauda.devops.java.client.models.V1alpha1CodeRepoService;
import hudson.model.Describable;
import io.alauda.jenkins.devops.sync.client.Clients;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;

import static io.alauda.jenkins.devops.sync.constants.Constants.*;
import com.cloudbees.plugins.credentials.impl.BaseStandardCredentials;
import io.alauda.devops.java.client.models.V1alpha1SecretKeySetRef;
import hudson.security.ACL;
import hudson.util.Secret;
import java.util.Optional;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.domains.Domain;
import static java.util.Arrays.asList;
import com.cloudbees.plugins.credentials.domains.DomainSpecification;
import com.cloudbees.plugins.credentials.domains.HostnameSpecification;
import com.cloudbees.plugins.credentials.domains.SchemeSpecification;
import java.net.URI;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import jenkins.model.Jenkins;
import java.util.Collections;

import java.util.List;

@Extension
@Restricted(NoExternalUse.class)
public class GitLabConfigServers implements GitProviderConfigServers {

	private static final Logger LOGGER = LoggerFactory.getLogger(GitLabConfigServers.class);

	@Override
	public boolean accept(String type) {
		return (CodeRepoServices.Gitlab.name().equals(type));
	}

	@Override
	public boolean createOrUpdateServer(V1alpha1CodeRepoBinding binding) {
		BaseStandardCredentials credentials = null;
		String serverName = String.format("%s-%s", binding.getMetadata().getNamespace(),
				binding.getMetadata().getName());
		String serverUrl = Clients.get(V1alpha1CodeRepoService.class).lister()
				.get(binding.getSpec().getCodeRepoService().getName()).getSpec().getHttp().getHost();

		try {
			Class<?> serversClz = loadClass(GITLAB_SOURCE_CONFIG_SERVERS);
			GlobalConfiguration servers = (GlobalConfiguration) serversClz.getMethod("get").invoke(null);

			Class<?> serverClz = loadClass(GITLAB_SOURCE_CONFIG_SERVER);
			String credentialId = getCredentialId(binding.getSpec().getAccount().getSecret());

			String personalAccessToken = getToken(credentialId);
			Describable<?> server = (Describable<?>) serversClz.getMethod("findServer", String.class).invoke(servers,
					serverName);
			if (server != null) {
				credentials = (BaseStandardCredentials) serverClz.getMethod("getCredentials").invoke(server);
				String host = (String) serverClz.getMethod("getServerUrl").invoke(server);
				if (credentials != null) {
					Secret token = (Secret) credentials.getClass().getMethod("getToken").invoke(credentials);
					if (Secret.toString(token).equals(personalAccessToken) && host.equals(serverUrl)) {
						return true;
					}
				}
			} else {
				server = (Describable<?>) serverClz.getConstructor(String.class, String.class, String.class)
						.newInstance(serverUrl, serverName, credentialId);
			}
			createOrUpdateCredentials(serverUrl, credentialId, personalAccessToken, credentials);
			serverClz.getMethod("setManageWebHooks", boolean.class).invoke(server, true);

			if (!(boolean) serversClz.getMethod("addServer", serverClz).invoke(servers, server)) {
				return (boolean) serversClz.getMethod("updateServer", serverClz).invoke(servers, server);
			}

		} catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException | SecurityException e) {
			LOGGER.warn("Exception happened while createOrUpdateServer", e);
		} catch (Throwable t) {
			LOGGER.warn("Exception happened while createOrUpdateServer", t);
		}
		return true;
	}

	@Override
	public boolean deleteServer(String namespace, String name) {
		try {
			Class<?> serversClz = loadClass(GITLAB_SOURCE_CONFIG_SERVERS);
			GlobalConfiguration servers = (GlobalConfiguration) serversClz.getMethod("get").invoke(null);
			serversClz.getMethod("removeServer", String.class).invoke(servers, String.format("%s-%s", namespace, name));
			return true;
		} catch (ClassNotFoundException | IllegalAccessException | NoSuchMethodException | SecurityException e) {
			LOGGER.warn("Exception happened while deleteServer", e);
		} catch (Throwable t) {
			LOGGER.warn("Exception happened while deleteServer", t);
		}
		return false;
	}

	private String getCredentialId(V1alpha1SecretKeySetRef secret) {
		return String.format("%s-%s", secret.getNamespace(), secret.getName());
	}

	private String getToken(String credentialId) {
		UsernamePasswordCredentialsImpl credential = CredentialsMatchers.firstOrNull(
				CredentialsProvider.lookupCredentials(UsernamePasswordCredentialsImpl.class, Jenkins.getInstance(),
						ACL.SYSTEM, Collections.<DomainRequirement>emptyList()),
				CredentialsMatchers.withId(credentialId));
		if (credential == null) {
			return "";
		}
		return Secret.toString(credential.getPassword());
	}

	private void createOrUpdateCredentials(String serverUrl, String credentialId, String token, Credentials oldCredentials)
			throws Exception {
		String description = String.format("Auto Generated by %s server for %s ", serverUrl, credentialId);
		Class<?> tokenClz = loadClass(GITLAB_SOURCE_CONFIG_SERVER_TOKEN);
		BaseStandardCredentials newCredentials = (BaseStandardCredentials) tokenClz
				.getConstructor(CredentialsScope.class, String.class, String.class, String.class)
				.newInstance(CredentialsScope.GLOBAL, credentialId, description, token);

		URI serverUri = URI.create(serverUrl);
		Domain domain = new SystemCredentialsProvider.StoreImpl().getDomainByName(serverUri.getHost());

		if (domain != null) {
			if (oldCredentials != null) {
				new SystemCredentialsProvider.StoreImpl().updateCredentials(domain, oldCredentials, newCredentials);
				return;
			}
			Optional<Credentials> credentialOpl = new SystemCredentialsProvider.StoreImpl().getCredentials(domain)
					.stream().filter(c -> {
						return ((BaseStandardCredentials) c).getId().equals(credentialId);
					}).findFirst();
			if (credentialOpl.isPresent()) {
				oldCredentials = credentialOpl.get();
			}
			if (oldCredentials == null) {
				new SystemCredentialsProvider.StoreImpl().addCredentials(domain, newCredentials);
			}
		} else {
			List<DomainSpecification> specifications = asList(new SchemeSpecification(serverUri.getScheme()),
					new HostnameSpecification(serverUri.getHost(), null));
			domain = new Domain(serverUri.getHost(), "GitLab domain (autogenerated)", specifications);
			new SystemCredentialsProvider.StoreImpl().addDomain(domain, newCredentials);
		}
	}
}