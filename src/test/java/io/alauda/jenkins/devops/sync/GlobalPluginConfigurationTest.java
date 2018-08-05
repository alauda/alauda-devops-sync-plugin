package io.alauda.jenkins.devops.sync;

import com.gargoylesoftware.htmlunit.html.*;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;

import static org.junit.Assert.*;

public class GlobalPluginConfigurationTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void basicTest() throws IOException, SAXException {
        JenkinsRule.WebClient wc = j.createWebClient();

        HtmlPage page = wc.goTo("configure");

        // elements exists check
        assertNotNull(page.getElementByName("_.server"));
        assertNotNull(page.getElementByName("_.credentialsId"));
        assertNotNull(page.getElementByName("_.jenkinsService"));
        assertNotNull(page.getElementByName("_.enabled"));

        assertTrue(GlobalPluginConfiguration.isItEnabled());

        GlobalPluginConfiguration config = GlobalPluginConfiguration.get();
        assertNotNull(config);
        assertNotNull(config.getDisplayName());
        assertNotNull(config.getNamespaces());

        config.reWatchAllNamespace("fake-ns");
        assertTrue(config.isEnabled());
    }

    @Test
    public void saveTest() throws IOException, SAXException {
        JenkinsRule.WebClient wc = j.createWebClient();

        {
            HtmlPage page = wc.goTo("configure");
            String jenkinsService = "alauda";

            HtmlCheckBoxInput elementEnabled = page.getElementByName("_.enabled");
            elementEnabled.setChecked(true);
            HtmlTextInput elementService = page.getElementByName("_.jenkinsService");
            elementService.setValueAttribute(jenkinsService);
            HtmlForm form = page.getFormByName("config");
            HtmlFormUtil.submit(form);

            assertTrue(GlobalPluginConfiguration.get().isEnabled());
            assertEquals(GlobalPluginConfiguration.get().getJenkinsService(), jenkinsService);
        }

        {
            HtmlPage page = wc.goTo("configure");

            HtmlCheckBoxInput elementEnabled = page.getElementByName("_.enabled");
            elementEnabled.setChecked(false);
            HtmlForm form = page.getFormByName("config");
            HtmlFormUtil.submit(form);

            assertFalse(GlobalPluginConfiguration.get().isEnabled());
        }
    }
}
