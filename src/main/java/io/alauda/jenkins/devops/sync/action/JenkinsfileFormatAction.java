package io.alauda.jenkins.devops.sync.action;

import hudson.Extension;
import hudson.model.UnprotectedRootAction;
import hudson.util.HttpResponses;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTPipelineDef;
import org.jenkinsci.plugins.pipeline.modeldefinition.parser.Converter;
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

        String unformattedGroovyPipeline = req.getParameter("jenkinsfile");

        if (!StringUtils.isEmpty(unformattedGroovyPipeline)) {
            ModelASTPipelineDef pipelineDef = Converter.scriptToPipelineDef(unformattedGroovyPipeline);
            if (pipelineDef != null) {
                result.accumulate("result", "success");
                result.accumulate("jenkinsfile", pipelineDef.toPrettyGroovy());
            } else {
                reportFailure(result, "Jenkinsfile content '" + unformattedGroovyPipeline + "' did not contain the 'pipeline' step");
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
