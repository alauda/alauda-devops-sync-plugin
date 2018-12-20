package io.alauda.jenkins.devops.sync.action;

import hudson.Extension;
import hudson.model.RootAction;
import hudson.security.csrf.CrumbExclusion;
import hudson.util.HttpResponses;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.pipeline.modeldefinition.ast.ModelASTPipelineDef;
import org.jenkinsci.plugins.pipeline.modeldefinition.parser.Converter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.interceptor.RequirePOST;
import org.parboiled.common.StringUtils;

import javax.annotation.CheckForNull;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Extension
public class JenkinsfileFormatAction implements RootAction {

    public static final String FORMATTER_URL = "alauda-formatter";


    @SuppressWarnings("unused")
    @RequirePOST
    public HttpResponse doFormatJenkinsfile(StaplerRequest req) {
        JSONObject result = new JSONObject();

        String unformattedGroovyPipeline = req.getParameter("jenkinsfile");

        if (!StringUtils.isEmpty(unformattedGroovyPipeline)) {
            ModelASTPipelineDef pipelineDef = Converter.scriptToPipelineDef(unformattedGroovyPipeline);
            if (pipelineDef != null) {
                result.accumulate("result", "success");
                result.accumulate("json", pipelineDef.toPrettyGroovy());
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

    // Add exception to CSRF protection
    @Extension
    public static class ModelConverterActionCrumbExclusion extends CrumbExclusion {
        @Override
        public boolean process(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
                throws IOException, ServletException {
            String pathInfo = req.getPathInfo();

            if (pathInfo != null && pathInfo.startsWith("/" + FORMATTER_URL + "/")) {
                chain.doFilter(req, resp);
                return true;
            }

            return false;
        }
    }
}
