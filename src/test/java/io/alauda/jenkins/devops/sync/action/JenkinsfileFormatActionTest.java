package io.alauda.jenkins.devops.sync.action;

import com.gargoylesoftware.htmlunit.HttpMethod;
import com.gargoylesoftware.htmlunit.WebRequest;
import com.google.common.base.Strings;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.io.IOException;
import java.net.URL;

import static org.junit.Assert.*;


public class JenkinsfileFormatActionTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void testFailedWhenNoParams() throws IOException {
        JenkinsRule.WebClient wc = j.createWebClient();

        WebRequest req = new WebRequest(new URL(wc.getContextPath() + JenkinsfileFormatAction.FORMATTER_URL + "/formatJenkinsfile"), HttpMethod.POST);
        String rawResult = wc.getPage(req).getWebResponse().getContentAsString();
        assertNotNull(rawResult);

        JSONObject result = JSONObject.fromObject(rawResult);
        assertEquals("Full result doesn't include status - " + result.toString(2), "ok", result.getString("status"));
        JSONObject resultData = result.getJSONObject("data");
        assertNotNull(resultData);
        assertEquals("Result wasn't a failure - " + result.toString(2), "failure", resultData.getString("result"));

        String expectedError = "No content found for 'jenkinsfile' parameter";
        assertTrue("Errors array (" + resultData.getJSONArray("errors").toString(2) + ") didn't contain expected error '" + expectedError + "'",
                foundExpectedErrorInJSON(resultData.getJSONArray("errors"), expectedError));
    }

    @Test
    public void testFormatJenkinsfile() throws IOException {
        JenkinsRule.WebClient wc = j.createWebClient();

        WebRequest req = new WebRequest(new URL(wc.getContextPath() + JenkinsfileFormatAction.FORMATTER_URL + "/formatJenkinsfile"), HttpMethod.POST);

        String jenkinsfile = IOUtils.toString(getClass().getResourceAsStream("unformattedJenkinsfile"));
        req.setRequestBody("jenkinsfile=" + jenkinsfile);

        String rawResult = wc.getPage(req).getWebResponse().getContentAsString();
        assertNotNull(rawResult);

        JSONObject result = JSONObject.fromObject(rawResult);
        assertEquals("Full result doesn't include status - " + result.toString(2), "ok", result.getString("status"));
        JSONObject resultData = result.getJSONObject("data");
        assertNotNull(resultData);
        assertEquals("Result wasn't a success - " + result.toString(2), "success", resultData.getString("result"));

        String expectedJenkinsfile = IOUtils.toString(getClass().getResourceAsStream("expectedJenkinsfile"));

        assertEquals(expectedJenkinsfile, resultData.getString("jenkinsfile"));
    }

    private boolean foundExpectedErrorInJSON(JSONArray errors, String expectedError) {
        for (int i = 0; i < errors.size(); i++) {
            if (errors.getJSONObject(i).getString("error").equals(expectedError)) {
                return true;
            }
        }
        return false;
    }

}
