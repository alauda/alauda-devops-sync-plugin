package io.alauda.jenkins.devops.sync.action;

import com.cloudbees.groovy.cps.NonCPS;
import hudson.model.Action;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.annotation.CheckForNull;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;
import org.kohsuke.stapler.export.ExportedBean;

@ExportedBean
public class PipelineAction implements Action {
  private List<Information> items;

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
    return null;
  }

  @Whitelisted
  @NonCPS
  public List<Information> getItems() {
    return items;
  }

  public static class Information {
    private String name;
    private String type;
    private String desc;
    private Object value;

    public Information(String name, Object value, String type, String desc) {
      this.name = name;
      this.type = type;
      this.desc = desc;
      this.value = value;
    }

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getType() {
      return type;
    }

    public void setType(String type) {
      this.type = type;
    }

    public String getDesc() {
      return desc;
    }

    public void setDesc(String desc) {
      this.desc = desc;
    }

    public Object getValue() {
      return value;
    }

    public void setValue(Object value) {
      this.value = value;
    }
  }

  public PipelineAction(Information... info) {
    items = Arrays.asList(info);
  }

  public PipelineAction(List<Information> info) {
    items = info;
  }

  public PipelineAction() {
    items = new ArrayList<>();
  }
}
