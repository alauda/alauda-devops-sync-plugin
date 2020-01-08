package io.alauda.jenkins.devops.sync.var;

import io.alauda.jenkins.devops.sync.constants.AnnotationProvider;
import java.util.Map;
import org.jenkinsci.plugins.scriptsecurity.sandbox.whitelists.Whitelisted;

/** Return the annotation、name and namespace of the pipelineconfig */
public class AlaudaContext {

  private String namespace;
  private String name;
  private Map<String, String> data;
  private Boolean issupport;

  public AlaudaContext(String name, String namespace, Map data, Boolean issupport) {
    this.name = name;
    this.namespace = namespace;
    this.data = data;
    this.issupport = issupport;
  }

  @Whitelisted
  public String getNamespace() {
    return namespace;
  }

  @Whitelisted
  public String getName() {
    return name;
  }

  @Whitelisted
  public String getItem(String key) {
    if (data != null) {
      String result = data.get(AnnotationProvider.getInstance().annotationPipelineContext() + key);
      if (result != null) {
        return result;
      } else {
        return "";
      }
    }
    return "";
  }

  @Whitelisted
  public boolean isSupport() {
    return issupport;
  }
}
