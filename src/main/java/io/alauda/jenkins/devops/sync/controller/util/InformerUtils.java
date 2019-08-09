package io.alauda.jenkins.devops.sync.controller.util;

import com.google.gson.reflect.TypeToken;
import io.alauda.devops.java.client.apis.DevopsAlaudaIoV1alpha1Api;
import io.alauda.devops.java.client.models.V1alpha1CodeRepository;
import io.alauda.devops.java.client.models.V1alpha1CodeRepositoryList;
import io.kubernetes.client.ApiException;
import io.kubernetes.client.informer.SharedIndexInformer;
import io.kubernetes.client.informer.SharedInformerFactory;
import org.apache.commons.lang.reflect.FieldUtils;

import java.lang.reflect.Field;
import java.lang.reflect.Type;
import java.util.Map;

public final class InformerUtils {
    private static final String INFORMER_STOPPED_FIELD_NAME = "stopped";

    private InformerUtils() {
    }

//    public static <ApiType> boolean isStopped(SharedIndexInformer<ApiType> informer, Class clazz) throws IllegalAccessException {
//        if (informer == null) {
//            return true;
//        }
//
//        Field stoppedField = FieldUtils.getDeclaredField(clazz, INFORMER_STOPPED_FIELD_NAME);
//        return stoppedField.getBoolean(informer);
//    }


    public static <ApiType> SharedIndexInformer<ApiType> getExistingSharedIndexInformer(SharedInformerFactory factory, Class<ApiType> apiTypeClass) {
        SharedIndexInformer<ApiType> informer = null;
        try {
            Field f = factory.getClass().getDeclaredField("informers");
            f.setAccessible(true);
            Map<Type, SharedIndexInformer> informers = (Map<Type, SharedIndexInformer>) f.get(factory);
            if (informers != null) {
                informer = informers.get(TypeToken.get(apiTypeClass).getType());
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            e.printStackTrace();
        }
        return informer;
    }
}