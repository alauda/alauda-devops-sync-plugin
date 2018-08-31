package io.alauda.jenkins.devops.sync;

import org.jvnet.hudson.test.recipes.Recipe;
import org.jvnet.hudson.test.recipes.WithPlugin;

import java.lang.annotation.*;

@Retention(RetentionPolicy.RUNTIME)
@Documented
@Target({ElementType.METHOD})
@Recipe(WithoutK8s.RunnerImpl.class)
public @interface WithoutK8s {
    public static class RunnerImpl extends Recipe.Runner<WithPlugin> {
        public RunnerImpl(){}
    }
}
