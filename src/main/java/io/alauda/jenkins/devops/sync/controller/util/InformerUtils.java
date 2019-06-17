package io.alauda.jenkins.devops.sync.controller.util;

import io.kubernetes.client.informer.SharedIndexInformer;
import org.apache.commons.lang.reflect.FieldUtils;

import java.lang.reflect.Field;

public final class InformerUtils {
    private static final String INFORMER_STOPPED_FIELD_NAME = "stopped";

    private InformerUtils() {}

//    public static <ApiType> boolean isStopped(SharedIndexInformer<ApiType> informer, Class clazz) throws IllegalAccessException {
//        if (informer == null) {
//            return true;
//        }
//
//        Field stoppedField = FieldUtils.getDeclaredField(clazz, INFORMER_STOPPED_FIELD_NAME);
//        return stoppedField.getBoolean(informer);
//    }

}
