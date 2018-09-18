package io.alauda.jenkins.devops.sync.listener;

import hudson.Extension;
import hudson.XmlFile;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import io.alauda.jenkins.devops.sync.AlaudaSyncGlobalConfiguration;

@Extension
public class GlobalConfigListener extends SaveableListener {
    @Override
    public void onChange(Saveable o, XmlFile file) {
       if(o instanceof AlaudaSyncGlobalConfiguration) {
           AlaudaSyncGlobalConfiguration.get().configChange();
       }
    }
}
