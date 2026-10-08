package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.PinnedEvidence;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;

/**
 * PostgreSQL evidence: pinned artifacts in a content-addressed store whose digests are re-checked on every
 * read, per-game step streams, and raw events deduplicated by source and event identifier.
 */
public class EvidenceLedgerAdapter implements EvidenceLedger {

    private static final String JSON = "application/json";
    private static final String TEXT = "text/plain";

    private static final String STEP_COLUMNS =
            "step, seat_index, window_key, phase, era, round, logical_time, observation, decision, outcome,"
                    + " outcome_code, entropy";
    private static final String EVENT_COLUMNS =
            "e.source, e.partition_no, e.offset_no, e.event_id, e.event_type, e.aggregate_id, e.aggregate_type,"
                    + " e.game_id, e.occurred_at, e.version, e.payload_artifact, a.content AS payload_content";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public EvidenceLedgerAdapter(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.clock = clock;
    }

    @Override
    public void pin(UUID scopeId, String manifestDigest, String manifestJson, Instant now) {
        transactions.executeWithoutResult(status -> {
            var artifact = putArtifact(manifestJson.getBytes(StandardCharsets.UTF_8), JSON, now);
            jdbc.update(
                    "INSERT INTO case_evidence (scope_id, manifest_digest, manifest_artifact, created_at)"
                            + " VALUES (?, ?, ?, ?) ON CONFLICT (scope_id) DO NOTHING",
                    scopeId,
                    manifestDigest,
                    artifact,
                    Rows.timestamp(now));
        });
    }

    @Override
    public void seal(UUID scopeId, String transcript, String resultDigest, Instant now) {
        transactions.executeWithoutResult(status -> {
            var artifact = putArtifact(transcript.getBytes(StandardCharsets.UTF_8), TEXT, now);
            var sealed = jdbc.update(
                    "UPDATE case_evidence SET transcript_artifact = ?, result_digest = ?, sealed_at = ?"
                            + " WHERE scope_id = ?",
                    artifact,
                    resultDigest,
                    Rows.timestamp(now),
                    scopeId);
            if (sealed == 0) {
                throw new IllegalStateException("Evidence of " + scopeId + " was sealed before it was pinned");
            }
        });
    }

    @Override
    public Optional<PinnedEvidence> pinned(UUID scopeId) {
        return jdbc
                .query(
                        "SELECT manifest_digest, manifest_artifact, transcript_artifact, result_digest"
                                + " FROM case_evidence WHERE scope_id = ? AND sealed_at IS NOT NULL",
                        (rs, i) -> new PinnedEvidence(
                                rs.getString("manifest_digest"),
                                artifactText(rs.getString("manifest_artifact")),
                                artifactText(rs.getString("transcript_artifact")),
                                rs.getString("result_digest")),
                        scopeId)
                .stream()
                .findFirst();
    }

    @Override
    public int append(UUID scopeId, UUID gameId, UUID attemptId, StepRecord step) {
        return jdbc.queryForObject(
                "INSERT INTO evidence_step (scope_id, game_id, step, attempt_id, seat_index, window_key, phase, era,"
                        + " round, logical_time, observation, decision, outcome, outcome_code, entropy, recorded_at)"
                        + " SELECT ?, ?, COALESCE(MAX(step) + 1, 0), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?"
                        + " FROM evidence_step WHERE scope_id = ? AND game_id = ? RETURNING step",
                Integer.class,
                scopeId,
                gameId,
                attemptId,
                step.seatIndex(),
                step.windowKey(),
                step.phase(),
                step.era(),
                step.round(),
                Rows.timestamp(step.logicalTime()),
                step.observation(),
                step.decision(),
                step.outcome().name(),
                step.outcomeCode(),
                step.entropy(),
                Rows.timestamp(clock.instant()),
                scopeId,
                gameId);
    }

    @Override
    public void resolve(UUID scopeId, UUID gameId, int seatIndex, String windowKey, StepOutcome outcome, String code) {
        jdbc.update(
                "UPDATE evidence_step SET outcome = ?, outcome_code = ? WHERE scope_id = ? AND game_id = ?"
                        + " AND seat_index = ? AND window_key = ? AND outcome = 'UNACKNOWLEDGED'",
                outcome.name(),
                code,
                scopeId,
                gameId,
                seatIndex,
                windowKey);
    }

    @Override
    public List<StepRecord> steps(UUID scopeId, UUID gameId) {
        return jdbc.query(
                "SELECT " + STEP_COLUMNS + " FROM evidence_step WHERE scope_id = ? AND game_id = ? ORDER BY step",
                (rs, i) -> step(rs),
                scopeId,
                gameId);
    }

    @Override
    public boolean retain(UUID scopeId, UUID attemptId, ObservedEvent event) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            var now = clock.instant();
            var payload = putArtifact(event.payload().getBytes(StandardCharsets.UTF_8), JSON, now);
            var inserted = jdbc.update(
                    "INSERT INTO evidence_event (scope_id, game_id, source, event_id, attempt_id, event_type,"
                            + " aggregate_id, aggregate_type, occurred_at, version, partition_no, offset_no,"
                            + " payload_artifact, observed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
                            + " ON CONFLICT (scope_id, game_id, source, event_id) DO NOTHING",
                    scopeId,
                    event.gameId(),
                    event.source(),
                    event.eventId(),
                    attemptId,
                    event.eventType(),
                    event.aggregateId(),
                    event.aggregateType(),
                    Rows.timestamp(event.occurredAt()),
                    event.version(),
                    event.partition(),
                    event.offset(),
                    payload,
                    Rows.timestamp(now));
            return inserted == 1;
        }));
    }

    @Override
    public void advance(UUID scopeId, UUID gameId, String source, int partition, long nextOffset) {
        jdbc.update(
                "INSERT INTO evidence_source_offset (scope_id, game_id, source, partition_no, next_offset)"
                        + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (scope_id, game_id, source, partition_no)"
                        + " DO UPDATE SET next_offset = GREATEST(evidence_source_offset.next_offset,"
                        + " EXCLUDED.next_offset)",
                scopeId,
                gameId,
                source,
                partition,
                nextOffset);
    }

    @Override
    public List<ObservedEvent> events(UUID scopeId, UUID gameId) {
        return jdbc.query(
                "SELECT " + EVENT_COLUMNS + " FROM evidence_event e LEFT JOIN evidence_artifact a"
                        + " ON a.digest = e.payload_artifact WHERE e.scope_id = ? AND e.game_id = ?"
                        + " ORDER BY e.source COLLATE \"C\", e.partition_no, e.offset_no",
                (rs, i) -> event(rs),
                scopeId,
                gameId);
    }

    @Override
    public Map<SourcePartition, Long> offsets(UUID scopeId, UUID gameId) {
        var offsets = new LinkedHashMap<SourcePartition, Long>();
        jdbc.query(
                "SELECT source, partition_no, next_offset FROM evidence_source_offset WHERE scope_id = ?"
                        + " AND game_id = ? ORDER BY source COLLATE \"C\", partition_no",
                rs -> {
                    offsets.put(
                            new SourcePartition(rs.getString("source"), rs.getInt("partition_no")),
                            rs.getLong("next_offset"));
                },
                scopeId,
                gameId);
        return offsets;
    }

    @Override
    public void purge(UUID scopeId) {
        transactions.executeWithoutResult(status -> {
            jdbc.update("DELETE FROM evidence_step WHERE scope_id = ?", scopeId);
            jdbc.update("DELETE FROM evidence_event WHERE scope_id = ?", scopeId);
            jdbc.update("DELETE FROM evidence_source_offset WHERE scope_id = ?", scopeId);
            jdbc.update("DELETE FROM case_evidence WHERE scope_id = ?", scopeId);
        });
    }

    private String putArtifact(byte[] content, String mediaType, Instant now) {
        var digest = sha256(content);
        jdbc.update(
                "INSERT INTO evidence_artifact (digest, media_type, content, created_at) VALUES (?, ?, ?, ?)"
                        + " ON CONFLICT (digest) DO NOTHING",
                digest,
                mediaType,
                content,
                Rows.timestamp(now));
        return digest;
    }

    /** Reads an artifact and refuses content that no longer hashes to its address. */
    private String artifactText(String digest) {
        if (digest == null) {
            throw new ManifestMismatchException("A pinned artifact is missing");
        }
        var content = jdbc
                .query("SELECT content FROM evidence_artifact WHERE digest = ?", (rs, i) -> rs.getBytes(1), digest)
                .stream()
                .findFirst()
                .orElse(null);
        return verified(digest, content);
    }

    /** Refuses content that is missing or no longer hashes to its address. */
    private static String verified(String digest, byte[] content) {
        if (content == null) {
            throw new ManifestMismatchException("Pinned artifact " + digest + " is unavailable");
        }
        if (!sha256(content).equals(digest)) {
            throw new ManifestMismatchException("Pinned artifact " + digest + " does not match its content address");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private StepRecord step(ResultSet rs) throws SQLException {
        return new StepRecord(
                rs.getInt("step"),
                rs.getInt("seat_index"),
                rs.getString("window_key"),
                rs.getString("phase"),
                (Integer) rs.getObject("era"),
                (Integer) rs.getObject("round"),
                Rows.instant(rs, "logical_time"),
                rs.getString("observation"),
                rs.getString("decision"),
                StepOutcome.valueOf(rs.getString("outcome")),
                rs.getString("outcome_code"),
                rs.getString("entropy"));
    }

    private ObservedEvent event(ResultSet rs) throws SQLException {
        return new ObservedEvent(
                rs.getString("source"),
                rs.getInt("partition_no"),
                rs.getLong("offset_no"),
                rs.getObject("event_id", UUID.class),
                rs.getString("event_type"),
                rs.getObject("aggregate_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getObject("game_id", UUID.class),
                Rows.instant(rs, "occurred_at"),
                rs.getInt("version"),
                verified(rs.getString("payload_artifact"), rs.getBytes("payload_content")));
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
