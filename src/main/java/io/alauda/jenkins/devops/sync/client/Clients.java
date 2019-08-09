package io.alauda.jenkins.devops.sync.client;

import java.util.concurrent.ConcurrentHashMap;

public class Clients {
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
}
