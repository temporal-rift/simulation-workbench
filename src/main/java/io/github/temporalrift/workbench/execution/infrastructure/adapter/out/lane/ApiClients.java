package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

/**
 * Creates the generated HTTP clients of one service for one authenticated caller. Each seat's bot and the
 * operator have separate credentials, so a client is always bound to exactly one bearer token.
 */
public interface ApiClients {

    <T> T create(Class<T> api, String baseUrl, String bearerToken);
}
