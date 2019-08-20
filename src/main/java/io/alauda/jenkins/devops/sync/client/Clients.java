package io.alauda.jenkins.devops.sync.client;


import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class Clients {
    private static ConcurrentHashMap<Class, ResourceClient> registeredClients = new ConcurrentHashMap<>();

    private Clients() {
    }

    public static <ApiType> void register(Class<ApiType> apiType, ResourceClient<ApiType> client) {
        registeredClients.put(apiType, client);
    }

    public static boolean contains(Class apiType) {
        return registeredClients.containsKey(apiType);
    }

    @SuppressWarnings("unchecked")
    public static <ApiType> ResourceClient<ApiType> get(Class<ApiType> apiType) {
        return registeredClients.get(apiType);
    }

    public static ConcurrentMap<Class, ResourceClient> getRegisteredClients() {
        return registeredClients;
    }

    public static boolean allRegisteredResourcesSynced() {
        return Clients.getRegisteredClients()
                .entrySet()
                .stream()
                .allMatch(client -> client.getValue().informer().hasSynced());
    }

    public static boolean registeredResourceSynced(Class... classes) {
        return Clients.getRegisteredClients()
                .entrySet()
                .stream()
                .filter(classResourceClientEntry -> Arrays.asList(classes).contains(classResourceClientEntry.getKey()))
                .allMatch(classResourceClientEntry -> classResourceClientEntry.getValue().informer().hasSynced());
    }
}
