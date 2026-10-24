package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds each client once per API, endpoint and bearer token and hands the same instance to every later case.
 * It belongs to one lane and is discarded with it, so its tokens never outlive the lane's configuration.
 */
public class CachingApiClients implements ApiClients {

    private record Key(Class<?> api, String baseUrl, String bearerToken) {}

    private final ApiClients delegate;
    private final Map<Key, Object> clients = new ConcurrentHashMap<>();

    public CachingApiClients(ApiClients delegate) {
        this.delegate = delegate;
    }

    @Override
    public <T> T create(Class<T> api, String baseUrl, String bearerToken) {
        return api.cast(clients.computeIfAbsent(
                new Key(api, baseUrl, bearerToken), _ -> delegate.create(api, baseUrl, bearerToken)));
    }
}
