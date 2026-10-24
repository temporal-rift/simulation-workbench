package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.SessionApi;

class CachingApiClientsTest {

    private final AtomicInteger built = new AtomicInteger();
    private final CachingApiClients clients = new CachingApiClients(new ApiClients() {
        @Override
        public <T> T create(Class<T> api, String baseUrl, String bearerToken) {
            built.incrementAndGet();
            return mock(api);
        }
    });

    @Test
    void theSameApiEndpointAndTokenShareOneClient() {
        var first = clients.create(SessionApi.class, "http://game", "t1");
        var second = clients.create(SessionApi.class, "http://game", "t1");

        assertThat(second).isSameAs(first);
        assertThat(built.get()).isEqualTo(1);
    }

    @Test
    void aDifferentApiEndpointOrTokenGetsItsOwnClient() {
        var session = clients.create(SessionApi.class, "http://game", "t1");

        assertThat(clients.create(SessionApi.class, "http://game", "t2")).isNotSameAs(session);
        assertThat(clients.create(SessionApi.class, "http://other", "t1")).isNotSameAs(session);
        assertThat(clients.create(ProjectionApi.class, "http://game", "t1")).isNotNull();
        assertThat(built.get()).isEqualTo(4);
    }
}
