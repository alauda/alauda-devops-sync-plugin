package io.alauda.jenkins.devops.sync.credential;

import com.cloudbees.plugins.credentials.Credentials;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.CredentialsStore;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.gargoylesoftware.htmlunit.ElementNotFoundException;
import com.gargoylesoftware.htmlunit.html.DomNodeUtil;
import com.gargoylesoftware.htmlunit.html.HtmlForm;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import com.gargoylesoftware.htmlunit.html.HtmlSelect;
import hudson.security.SecurityRealm;
import io.alauda.jenkins.devops.sync.util.CredentialsUtils;
import jenkins.model.Jenkins;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

/**
 * @author suren
 */
public class CredentialTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void create() throws Exception {
        // create by java api
        String secretName = "id";
        CredentialsStore store = CredentialsProvider.lookupStores(Jenkins.getInstance()).iterator().next();
        Credentials token = CredentialsUtils.newTokenCredentials(secretName, "");
        store.addCredentials(Domain.global(), token);

        assertNotNull(CredentialsUtils.lookupCredentials(secretName));

        // create by web ui
        j.jenkins.setSecurityRealm(SecurityRealm.NO_AUTHENTICATION);
        JenkinsRule.WebClient wc = j.createWebClient();
        HtmlPage page = wc.goTo("credentials/store/system/domain/_/newCredentials");

        String credentialId = "id";
        HtmlForm form = page.getFormByName("newCredentials");

        HtmlSelect s = (HtmlSelect)DomNodeUtil.selectSingleNode(form, "//SELECT");
        s.getOptions().forEach(opt -> {
            if(opt.getText().equals("Alauda Token for Alauda Sync Plugin")) {
                s.setSelectedAttribute(opt, true);
            }
        });
        assertFalse(s.isMultipleSelectEnabled());
        assertEquals("Alauda Token for Alauda Sync Plugin", s.getSelectedOptions().get(0).getText());
        page.getEnclosingWindow().getJobManager().waitForJobsStartingBefore(500);

        form = page.getFormByName("newCredentials");
        for(int i = 0; i < 3; i++) {
            try{
                form.getInputByName("_.secret");
                break;
            } catch (ElementNotFoundException e){}
        }
        form.getInputByName("_.secret").setValueAttribute("test");
        form.getInputByName("_.id").setValueAttribute(credentialId);
        form.getInputByName("_.description").setValueAttribute(credentialId);
        // TODO need to fix 500
//        j.submit(form);
//
//        assertNotNull(CredentialsUtils.lookupCredentials(credentialId));
    }
}
