package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionState;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;

/** PostgreSQL reproductions: claimed under a lease, so a stalled worker can never settle a re-run. */
public class ReproductionRepositoryAdapter implements ReproductionRepository {

    private static final String COLUMNS =
            "reproduction_id, attempt_id, run_id, case_id, lane_id, state, divergence_json,"
                    + " failure_code, failure_message, created_at, finished_at";
    private static final String CLAIM_NEXT = "UPDATE reproduction SET state = 'RUNNING', lease_owner = ?,"
            + " lease_expires_at = ? WHERE reproduction_id = (SELECT reproduction_id FROM reproduction"
            + " WHERE state = 'QUEUED' AND lane_id = ANY (?) ORDER BY created_at LIMIT 1"
            + " FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS;
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public ReproductionRepositoryAdapter(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    @Override
    public ReproductionCreation create(UUID idempotencyKey, String requestHash, Reproduction reproduction) {
        return transactions.execute(status -> {
            var inserted = jdbc.update(
                    "INSERT INTO reproduction (reproduction_id, attempt_id, run_id, case_id, lane_id, idempotency_key,"
                            + " request_hash, state, created_at) VALUES (?, ?, ?, ?, ?, ?, ?, 'QUEUED', ?)"
                            + " ON CONFLICT (idempotency_key) DO NOTHING",
                    reproduction.reproductionId(),
                    reproduction.attemptId(),
                    reproduction.runId(),
                    reproduction.caseId(),
                    reproduction.laneId(),
                    idempotencyKey,
                    requestHash,
                    Rows.timestamp(reproduction.createdAt()));
            if (inserted == 1) {
                return new ReproductionCreation.Created();
            }
            return new ReproductionCreation.Existing(findByKey(idempotencyKey).orElseThrow());
        });
    }

    @Override
    public Optional<ReproductionClaim> findByKey(UUID idempotencyKey) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + ", request_hash FROM reproduction WHERE idempotency_key = ?",
                        (rs, i) -> new ReproductionClaim(reproduction(rs), rs.getString("request_hash")),
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Reproduction> find(UUID reproductionId) {
        return jdbc
                .query(
                        "SELECT " + COLUMNS + " FROM reproduction WHERE reproduction_id = ?",
                        (rs, i) -> reproduction(rs),
                        reproductionId)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<Reproduction> claimNext(
            String owner, Instant now, Instant leaseUntil, Collection<String> freeLaneIds) {
        if (freeLaneIds.isEmpty()) {
            return Optional.empty();
        }
        return jdbc
                .query(
                        connection -> {
                            var statement = connection.prepareStatement(CLAIM_NEXT);
                            statement.setString(1, owner);
                            statement.setObject(2, Rows.timestamp(leaseUntil));
                            statement.setArray(3, connection.createArrayOf("varchar", freeLaneIds.toArray()));
                            return statement;
                        },
                        (rs, i) -> reproduction(rs))
                .stream()
                .findFirst();
    }

    @Override
    public boolean extendLease(UUID reproductionId, String owner, Instant leaseUntil) {
        return jdbc.update(
                        "UPDATE reproduction SET lease_expires_at = ? WHERE reproduction_id = ?"
                                + " AND state = 'RUNNING' AND lease_owner = ?",
                        Rows.timestamp(leaseUntil),
                        reproductionId,
                        owner)
                == 1;
    }

    @Override
    public boolean settle(UUID reproductionId, String owner, Reproduction settled) {
        var failure = settled.failure();
        return jdbc.update(
                        "UPDATE reproduction SET state = ?, divergence_json = ?, failure_code = ?,"
                                + " failure_message = ?, finished_at = ?, lease_owner = NULL, lease_expires_at = NULL"
                                + " WHERE reproduction_id = ? AND state = 'RUNNING' AND lease_owner = ?",
                        settled.state().name(),
                        settled.firstDivergence() == null ? null : divergenceJson(settled.firstDivergence()),
                        failure == null ? null : failure.code().name(),
                        failure == null ? null : failure.message(),
                        Rows.timestamp(settled.finishedAt()),
                        reproductionId,
                        owner)
                == 1;
    }

    @Override
    public void release(UUID reproductionId, String owner) {
        jdbc.update(
                "UPDATE reproduction SET state = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL"
                        + " WHERE reproduction_id = ? AND state = 'RUNNING' AND lease_owner = ?",
                reproductionId,
                owner);
    }

    @Override
    public int requeueExpired(Instant now) {
        return jdbc.update(
                "UPDATE reproduction SET state = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL"
                        + " WHERE state = 'RUNNING' AND lease_expires_at < ?",
                Rows.timestamp(now));
    }

    @Override
    public int requeueOwnedBy(String owner) {
        return jdbc.update(
                "UPDATE reproduction SET state = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL"
                        + " WHERE state = 'RUNNING' AND lease_owner = ?",
                owner);
    }

    @Override
    public int requeueAllRunning() {
        return jdbc.update("UPDATE reproduction SET state = 'QUEUED', lease_owner = NULL, lease_expires_at = NULL"
                + " WHERE state = 'RUNNING'");
    }

    private Reproduction reproduction(ResultSet rs) throws SQLException {
        var divergence = rs.getString("divergence_json");
        var failureCode = rs.getString("failure_code");
        return new Reproduction(
                rs.getObject("reproduction_id", UUID.class),
                rs.getObject("attempt_id", UUID.class),
                rs.getObject("run_id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("lane_id"),
                ReproductionState.valueOf(rs.getString("state")),
                divergence == null ? null : divergence(divergence),
                failureCode == null
                        ? null
                        : new Failure(FailureCode.valueOf(failureCode), rs.getString("failure_message")),
                Rows.instant(rs, "created_at"),
                Rows.instant(rs, "finished_at"));
    }

    private String divergenceJson(Divergence divergence) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "step", divergence.step(),
                    "kind", divergence.kind(),
                    "expected", divergence.expected(),
                    "actual", divergence.actual()));
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot serialize the divergence", e);
        }
    }

    @SuppressWarnings("unchecked")
    private Divergence divergence(String json) {
        try {
            var stored = objectMapper.readValue(json, OBJECT);
            return new Divergence(
                    ((Number) stored.get("step")).intValue(),
                    (String) stored.get("kind"),
                    (Map<String, Object>) stored.get("expected"),
                    (Map<String, Object>) stored.get("actual"));
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored divergence is not valid JSON", e);
        }
    }
}
