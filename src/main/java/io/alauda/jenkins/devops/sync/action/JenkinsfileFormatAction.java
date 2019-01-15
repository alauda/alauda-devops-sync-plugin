package io.alauda.jenkins.devops.sync.action;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import io.alauda.jenkins.devops.sync.util.JenkinsUtils;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.parboiled.common.StringUtils;

import javax.annotation.CheckForNull;

@Extension
public class JenkinsfileFormatAction implements UnprotectedRootAction {

    public static final String FORMATTER_URL = "alaudaFormatter";


    @SuppressWarnings("unused")
    @RequirePOST
    public HttpResponse doFormatJenkinsfile(StaplerRequest req) {
        JSONObject result = new JSONObject();

        String jenkinsfile = req.getParameter("jenkinsfile");

        if (!StringUtils.isEmpty(jenkinsfile)) {
            try {
                String formattedJenkinsfile = JenkinsUtils.formatJenkinsfile(jenkinsfile);
                result.accumulate("result", "success");
                result.accumulate("jenkinsfile", formattedJenkinsfile);
            } catch (Exception e) {
                // also catch runtime exception
                e.printStackTrace();
                reportFailure(result, e.getMessage());
            }
        } else {
            reportFailure(result, "No content found for 'jenkinsfile' parameter");
        }

        return HttpResponses.okJSON(result);
    }

    private void reportFailure(JSONObject result, String message) {
        JSONArray errors = new JSONArray();
        JSONObject o = new JSONObject();
        o.accumulate("error", message);
        errors.add(o);
        reportFailure(result, errors);
    }

    private void reportFailure(JSONObject result, JSONArray errors) {
        result.accumulate("result", "failure");
        result.accumulate("errors", errors);
    }

    @CheckForNull
    @Override
    public String getIconFileName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getDisplayName() {
        return null;
    }

    @CheckForNull
    @Override
    public String getUrlName() {
        return FORMATTER_URL;
    }

}
