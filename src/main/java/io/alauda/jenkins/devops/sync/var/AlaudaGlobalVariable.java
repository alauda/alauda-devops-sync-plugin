package io.alauda.jenkins.devops.sync.var;

import com.alibaba.fastjson.JSON;
import hudson.model.ParametersAction;
import hudson.model.Run;
import hudson.model.Job;
import hudson.Extension;

import org.jenkinsci.plugins.workflow.cps.CpsScript;
import org.jenkinsci.plugins.workflow.cps.GlobalVariable;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import io.alauda.jenkins.devops.sync.WorkflowJobProperty;

import javax.annotation.Nonnull;
import javax.xml.soap.SAAJResult;
import java.util.Map;

/**
 * Allows access to {@link ParametersAction}.
 */
@Extension public class AlaudaGlobalVariable extends GlobalVariable {

    @Nonnull
    @Override
    public String getName(){
        return "alaudaContext";
    }
    @Nonnull
    @Override
    public Object getValue(@Nonnull CpsScript script) throws Exception{
        Run<?,?> build = script.$build();
        if (build == null) {
            throw new IllegalStateException("cannot find owning build");
        }

        Job<?,?> parent =  build.getParent();
        if(parent instanceof WorkflowJob){
            WorkflowJobProperty property = parent.getProperty(WorkflowJobProperty.class);
            if (property==null){
                Map data = JSON.parseObject("{}");
                return new AlaudaContext("","",data,false);
            }
            String namespace = property.getNamespace();
            String name = property.getName();

            Map data = JSON.parseObject(property.getContextAnnotation());
            return new AlaudaContext(name,namespace,data,true);
        }
        throw new IllegalStateException("not intance of WorkflowJob");
    }

}
