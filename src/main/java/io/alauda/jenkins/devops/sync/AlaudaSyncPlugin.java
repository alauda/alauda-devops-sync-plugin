package io.alauda.jenkins.devops.sync;

import hudson.Extension;
import hudson.Plugin;
import io.kubernetes.client.informer.cache.ProcessorListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

@Extension
public class AlaudaSyncPlugin extends Plugin {
    private Logger logger = LoggerFactory.getLogger(AlaudaSyncPlugin.class);

    @Override
    public void postInitialize() throws Exception {
        try {
            Field field = ProcessorListener.class.getDeclaredField("DEFAULT_QUEUE_CAPACITY");
            field.setAccessible(true);

            Field modifiersField = Field.class.getDeclaredField("modifiers");
            modifiersField.setAccessible(true);
            modifiersField.setInt(field, field.getModifiers() & ~Modifier.FINAL);

            logger.info("Start to change the default capacity of ProcessorListener, before: {}", field.get(null));
            field.setInt(null, 5000);
            logger.info("Start to change the default capacity of ProcessorListener, after: {}", field.get(null));
        } catch (Throwable e) {
            logger.error("Unable to use reflect to change the default capacity of ProcessorListener, reason: {}", e);
        }
    }
}
