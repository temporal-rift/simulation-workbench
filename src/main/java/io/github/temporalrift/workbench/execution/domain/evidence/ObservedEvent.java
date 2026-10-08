package io.github.temporalrift.workbench.execution.domain.evidence;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A raw game or timeline event as the observer saw it. {@code source} names the topic; partition and
 * offset locate the record within that source only, because no order across sources exists. The payload is
 * the record value exactly as delivered.
 */
public record ObservedEvent(
        String source,
        int partition,
        long offset,
        UUID eventId,
        String eventType,
        UUID aggregateId,
        String aggregateType,
        UUID gameId,
        Instant occurredAt,
        int version,
        String payload) {

    public ObservedEvent {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(gameId, "gameId");
        Objects.requireNonNull(payload, "payload");
    }
}
