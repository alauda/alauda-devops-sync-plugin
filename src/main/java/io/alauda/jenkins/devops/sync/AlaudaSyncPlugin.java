package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.Plugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Extension
public class AlaudaSyncPlugin extends Plugin {
    private Logger logger = LoggerFactory.getLogger(AlaudaSyncPlugin.class);

    @Override
    public void postInitialize() {
    }
}
