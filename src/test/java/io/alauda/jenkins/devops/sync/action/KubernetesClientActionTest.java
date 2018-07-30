package io.alauda.jenkins.devops.sync.action;

import com.gargoylesoftware.htmlunit.Page;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.gargoylesoftware.htmlunit.WebResponse;
import net.sf.json.JSON;
import net.sf.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.xml.sax.SAXException;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class KubernetesClientActionTest {
    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void doConnectTest() throws IOException, SAXException {
        JenkinsRule.WebClient wc = j.createWebClient();

        wc.getOptions().setThrowExceptionOnFailingStatusCode(false);

        {
            // valid check
            Page page = wc.goTo("alauda/connectTest", null);
            WebResponse response = page.getWebResponse();
            assertEquals(200, response.getStatusCode());

            String text = response.getContentAsString();
            JSONObject json = JSONObject.fromObject(text);

            assertEquals(json.get("status").toString(), "ok");
            assertTrue("kubernetes connect error",
                    json.getJSONObject("data").getBoolean("success"));
        }

        {
            // invalid check
            Page page = wc.goTo("alauda/connectTest?server=a&credentialId=a", null);
            WebResponse response = page.getWebResponse();
            assertEquals(200, response.getStatusCode());

            String text = response.getContentAsString();
            JSONObject json = JSONObject.fromObject(text);

            assertEquals(json.get("status").toString(), "ok");
            assertFalse("kubernetes connect error",
                    json.getJSONObject("data").getBoolean("success"));
        }
    }
}
