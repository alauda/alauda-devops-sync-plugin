package io.alauda.jenkins.devops.sync.client;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

public class Clients {
    private static Logger logger = LoggerFactory.getLogger(Clients.class);
    private static ConcurrentHashMap<Class, ResourceClient> clients = new ConcurrentHashMap<>();


    public static <ApiType> void register(Class<ApiType> apiType, ResourceClient<ApiType> client) {
        clients.put(apiType, client);
    }

    public static boolean contains(Class apiType) {
        return clients.containsKey(apiType);
    }

    @SuppressWarnings("unchecked")
    public static <ApiType> ResourceClient<ApiType> get(Class<ApiType> apiType) {
        return clients.get(apiType);
    }

    public static ConcurrentHashMap<Class, ResourceClient> getClients() {
        return clients;
    }

    public static boolean allRegisteredResourcesSynced() {
        return Clients.getClients()
                .entrySet()
                .stream()
                .allMatch(client -> client.getValue().informer().hasSynced());
    }

    public static boolean registeredResourceSynced(Class... classes) {
        return Clients.getClients()
                .entrySet()
                .stream()
                .filter(classResourceClientEntry -> Arrays.asList(classes).contains(classResourceClientEntry.getKey()))
                .allMatch(classResourceClientEntry -> classResourceClientEntry.getValue().informer().hasSynced());
    }
}
