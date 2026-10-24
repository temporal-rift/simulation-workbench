package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger.SourcePartition;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * Multi-row evidence writes: the payload artifacts and the events that reference them are stored together in one
 * transaction, each with one conflict-tolerant statement, and source offsets are upserted without ever moving back.
 */
@Component
class EvidenceBatchWrites {

    /** Keeps a statement under the driver's bind-parameter limit. */
    private static final int ROWS_PER_STATEMENT = 500;

    private static final String JSON = "application/json";

    private static final Table<?> ARTIFACT = DSL.table(DSL.name("evidence_artifact"));
    private static final Field<String> ARTIFACT_DIGEST = DSL.field(DSL.name("digest"), SQLDataType.VARCHAR);
    private static final Field<String> ARTIFACT_MEDIA_TYPE = DSL.field(DSL.name("media_type"), SQLDataType.VARCHAR);
    private static final Field<byte[]> ARTIFACT_CONTENT = DSL.field(DSL.name("content"), SQLDataType.BLOB);
    private static final Field<OffsetDateTime> ARTIFACT_CREATED =
            DSL.field(DSL.name("created_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private static final Table<?> EVENT = DSL.table(DSL.name("evidence_event"));
    private static final Field<UUID> SCOPE = DSL.field(DSL.name("scope_id"), SQLDataType.UUID);
    private static final Field<UUID> GAME = DSL.field(DSL.name("game_id"), SQLDataType.UUID);
    private static final Field<String> SOURCE = DSL.field(DSL.name("source"), SQLDataType.VARCHAR);
    private static final Field<UUID> EVENT_ID = DSL.field(DSL.name("event_id"), SQLDataType.UUID);
    private static final Field<UUID> ATTEMPT = DSL.field(DSL.name("attempt_id"), SQLDataType.UUID);
    private static final Field<String> EVENT_TYPE = DSL.field(DSL.name("event_type"), SQLDataType.VARCHAR);
    private static final Field<UUID> AGGREGATE_ID = DSL.field(DSL.name("aggregate_id"), SQLDataType.UUID);
    private static final Field<String> AGGREGATE_TYPE = DSL.field(DSL.name("aggregate_type"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> OCCURRED_AT =
            DSL.field(DSL.name("occurred_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);
    private static final Field<Integer> VERSION = DSL.field(DSL.name("version"), SQLDataType.INTEGER);
    private static final Field<Integer> PARTITION = DSL.field(DSL.name("partition_no"), SQLDataType.INTEGER);
    private static final Field<Long> OFFSET = DSL.field(DSL.name("offset_no"), SQLDataType.BIGINT);
    private static final Field<String> PAYLOAD = DSL.field(DSL.name("payload_artifact"), SQLDataType.VARCHAR);
    private static final Field<OffsetDateTime> OBSERVED_AT =
            DSL.field(DSL.name("observed_at"), SQLDataType.TIMESTAMPWITHTIMEZONE);

    private static final Table<?> SOURCE_OFFSET = DSL.table(DSL.name("evidence_source_offset"));
    private static final Field<Long> NEXT_OFFSET = DSL.field(DSL.name("next_offset"), SQLDataType.BIGINT);

    /** Qualified, because the target row and the excluded one are both in scope of the update. */
    private static final Field<Long> STORED_NEXT_OFFSET =
            DSL.field(DSL.name("evidence_source_offset", "next_offset"), SQLDataType.BIGINT);

    private record Row(ObservedEvent event, String digest) {}

    private final DSLContext dsl;

    EvidenceBatchWrites(DSLContext dsl) {
        this.dsl = dsl;
    }

    /** Stores the events not yet retained and the payloads not yet stored, and returns how many events were new. */
    @Transactional
    int retain(UUID scopeId, UUID attemptId, List<ObservedEvent> batch, Instant now) {
        var distinct = new LinkedHashMap<String, ObservedEvent>();
        batch.forEach(
                event -> distinct.putIfAbsent(event.gameId() + "|" + event.source() + "|" + event.eventId(), event));
        var payloads = new LinkedHashMap<String, byte[]>();
        var rows = distinct.values().stream()
                .map(event -> {
                    var content = event.payload().getBytes(StandardCharsets.UTF_8);
                    var digest = Sha256.hex(content);
                    payloads.putIfAbsent(digest, content);
                    return new Row(event, digest);
                })
                .toList();
        var at = now.atOffset(ZoneOffset.UTC);
        for (var chunk : chunks(List.copyOf(payloads.entrySet()))) {
            var insert =
                    dsl.insertInto(ARTIFACT, ARTIFACT_DIGEST, ARTIFACT_MEDIA_TYPE, ARTIFACT_CONTENT, ARTIFACT_CREATED);
            chunk.forEach(artifact -> insert.values(artifact.getKey(), JSON, artifact.getValue(), at));
            insert.onConflict(ARTIFACT_DIGEST).doNothing().execute();
        }
        var stored = 0;
        for (var chunk : chunks(rows)) {
            var insert = dsl.insertInto(
                    EVENT,
                    SCOPE,
                    GAME,
                    SOURCE,
                    EVENT_ID,
                    ATTEMPT,
                    EVENT_TYPE,
                    AGGREGATE_ID,
                    AGGREGATE_TYPE,
                    OCCURRED_AT,
                    VERSION,
                    PARTITION,
                    OFFSET,
                    PAYLOAD,
                    OBSERVED_AT);
            chunk.forEach(row -> {
                var event = row.event();
                insert.values(
                        scopeId,
                        event.gameId(),
                        event.source(),
                        event.eventId(),
                        attemptId,
                        event.eventType(),
                        event.aggregateId(),
                        event.aggregateType(),
                        event.occurredAt() == null ? null : event.occurredAt().atOffset(ZoneOffset.UTC),
                        event.version(),
                        event.partition(),
                        event.offset(),
                        row.digest(),
                        at);
            });
            stored +=
                    insert.onConflict(SCOPE, GAME, SOURCE, EVENT_ID).doNothing().execute();
        }
        return stored;
    }

    /** Raises every offset to at least the given one, creating the missing rows, in one upsert per chunk. */
    @Transactional
    void advance(UUID scopeId, UUID gameId, Map<SourcePartition, Long> nextOffsets) {
        for (var chunk : chunks(List.copyOf(nextOffsets.entrySet()))) {
            var insert = dsl.insertInto(SOURCE_OFFSET, SCOPE, GAME, SOURCE, PARTITION, NEXT_OFFSET);
            chunk.forEach(entry -> insert.values(
                    scopeId, gameId, entry.getKey().source(), entry.getKey().partition(), entry.getValue()));
            insert.onConflict(SCOPE, GAME, SOURCE, PARTITION)
                    .doUpdate()
                    .set(NEXT_OFFSET, DSL.greatest(STORED_NEXT_OFFSET, DSL.excluded(NEXT_OFFSET)))
                    .execute();
        }
    }

    private static <T> List<List<T>> chunks(List<T> rows) {
        return IntStream.range(0, (rows.size() + ROWS_PER_STATEMENT - 1) / ROWS_PER_STATEMENT)
                .mapToObj(
                        i -> rows.subList(i * ROWS_PER_STATEMENT, Math.min(rows.size(), (i + 1) * ROWS_PER_STATEMENT)))
                .toList();
    }
}
