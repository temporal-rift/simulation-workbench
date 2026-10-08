package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;

/**
 * PostgreSQL scheduling of logical cases. A run's row lock serializes the claims on it, so its
 * concurrency bound holds across workers; every outcome is fenced by the attempt's running state and
 * lease owner, so a worker whose attempt was interrupted or recovered can no longer write a result.
 */
public class CaseLedgerAdapter implements CaseLedger {

    private static final String CASE_COLUMNS =
            "case_id, run_id, case_key, ordinal, variant_label, seed, player_count, seats_json, state, result_json";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Rows rows;

    public CaseLedgerAdapter(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rows = new Rows(objectMapper);
    }

    @Override
    public Optional<Claim> claimNext(String owner, Instant now, Instant leaseUntil) {
        return transactions.execute(status -> {
            var runIds =
                    jdbc.queryForList("SELECT run_id FROM run WHERE state = 'RUNNING' ORDER BY created_at", UUID.class);
            for (var runId : runIds) {
                var claim = claimFromRun(runId, owner, now, leaseUntil);
                if (claim.isPresent()) {
                    return claim;
                }
            }
            return Optional.empty();
        });
    }

    private Optional<Claim> claimFromRun(UUID runId, String owner, Instant now, Instant leaseUntil) {
        var concurrency = jdbc.queryForList(
                "SELECT concurrency FROM run WHERE run_id = ? AND state = 'RUNNING' FOR UPDATE", Integer.class, runId);
        if (concurrency.isEmpty()) {
            return Optional.empty();
        }
        var running = jdbc.queryForObject(
                "SELECT count(*) FROM run_case WHERE run_id = ? AND state = 'RUNNING'", Integer.class, runId);
        if (running >= concurrency.getFirst()) {
            return Optional.empty();
        }
        var next = jdbc.query(
                "SELECT " + CASE_COLUMNS + " FROM run_case WHERE run_id = ? AND state = 'PENDING'"
                        + " ORDER BY ordinal LIMIT 1",
                (rs, i) -> rows.logicalCase(rs),
                runId);
        if (next.isEmpty()) {
            return Optional.empty();
        }
        var logicalCase = next.getFirst();
        jdbc.update("UPDATE run_case SET state = 'RUNNING' WHERE case_id = ?", logicalCase.caseId());
        var previous = latestAttempt(logicalCase.caseId());
        var attemptId = UUID.randomUUID();
        var ordinal = previous.map(Attempt::ordinal).orElse(0) + 1;
        jdbc.update(
                "INSERT INTO case_attempt (attempt_id, case_id, ordinal, state, lease_owner, lease_expires_at,"
                        + " started_at) VALUES (?, ?, ?, 'RUNNING', ?, ?, ?)",
                attemptId,
                logicalCase.caseId(),
                ordinal,
                owner,
                Rows.timestamp(leaseUntil),
                Rows.timestamp(now));
        var attempt = new Attempt(
                attemptId, logicalCase.caseId(), ordinal, AttemptState.RUNNING, null, null, now, null, null);
        return Optional.of(new Claim(
                new LogicalCase(
                        logicalCase.caseId(),
                        logicalCase.runId(),
                        logicalCase.caseKey(),
                        logicalCase.ordinal(),
                        logicalCase.variantLabel(),
                        logicalCase.seed(),
                        logicalCase.playerCount(),
                        logicalCase.seats(),
                        CaseState.RUNNING,
                        null),
                attempt,
                previous,
                concurrency.getFirst()));
    }

    private Optional<Attempt> latestAttempt(UUID caseId) {
        return jdbc
                .query(
                        "SELECT * FROM case_attempt WHERE case_id = ? ORDER BY ordinal DESC LIMIT 1",
                        (rs, i) -> rows.attempt(rs),
                        caseId)
                .stream()
                .findFirst();
    }

    @Override
    public boolean extendLease(UUID attemptId, String owner, Instant leaseUntil) {
        return jdbc.update(
                        "UPDATE case_attempt SET lease_expires_at = ? WHERE attempt_id = ? AND lease_owner = ?"
                                + " AND state = 'RUNNING'",
                        Rows.timestamp(leaseUntil),
                        attemptId,
                        owner)
                == 1;
    }

    @Override
    public void recordGame(UUID attemptId, UUID gameId, String laneId) {
        jdbc.update("UPDATE case_attempt SET game_id = ?, lane_id = ? WHERE attempt_id = ?", gameId, laneId, attemptId);
    }

    @Override
    public boolean succeed(UUID attemptId, String owner, CaseResult result, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!settleAttempt(attemptId, owner, "SUCCEEDED", now, null)) {
                return false;
            }
            jdbc.update(
                    "UPDATE run_case SET state = 'SUCCEEDED', result_json = ? WHERE case_id = ("
                            + "SELECT case_id FROM case_attempt WHERE attempt_id = ?) AND state = 'RUNNING'",
                    rows.json(result),
                    attemptId);
            return true;
        }));
    }

    @Override
    public FailOutcome fail(UUID attemptId, String owner, Failure failure, boolean retry, Instant now) {
        return transactions.execute(status -> {
            if (!settleAttempt(attemptId, owner, "FAILED", now, failure)) {
                return FailOutcome.LOST;
            }
            jdbc.update(
                    "UPDATE run_case SET state = ? WHERE case_id = (SELECT case_id FROM case_attempt"
                            + " WHERE attempt_id = ?) AND state = 'RUNNING'",
                    retry ? "PENDING" : "FAILED",
                    attemptId);
            return retry ? FailOutcome.RETRY : FailOutcome.CASE_FAILED;
        });
    }

    @Override
    public boolean cancel(UUID attemptId, String owner, Instant now) {
        return Boolean.TRUE.equals(transactions.execute(status -> {
            if (!settleAttempt(attemptId, owner, "CANCELLED", now, null)) {
                return false;
            }
            jdbc.update(
                    "UPDATE run_case SET state = 'CANCELLED' WHERE case_id = (SELECT case_id FROM case_attempt"
                            + " WHERE attempt_id = ?) AND state = 'RUNNING'",
                    attemptId);
            return true;
        }));
    }

    @Override
    public void release(UUID attemptId, String owner, Instant now) {
        transactions.executeWithoutResult(status -> {
            var caseIds = jdbc.queryForList(
                    "SELECT case_id FROM case_attempt WHERE attempt_id = ? AND lease_owner = ? AND state = 'RUNNING'",
                    UUID.class,
                    attemptId,
                    owner);
            caseIds.forEach(caseId -> {
                jdbc.update("DELETE FROM case_attempt WHERE attempt_id = ?", attemptId);
                jdbc.update("UPDATE run_case SET state = 'PENDING' WHERE case_id = ? AND state = 'RUNNING'", caseId);
            });
        });
    }

    @Override
    public int interruptExpired(Instant now) {
        return interrupt("a.state = 'RUNNING' AND a.lease_expires_at < ?", now, Rows.timestamp(now));
    }

    @Override
    public int interruptOwnedBy(String owner, Instant now) {
        return interrupt("a.state = 'RUNNING' AND a.lease_owner = ?", now, owner);
    }

    @Override
    public int interruptAllRunning(Instant now) {
        var interrupted = interrupt("a.state = 'RUNNING'", now);
        var idle = jdbc.update("UPDATE run SET state = 'INTERRUPTED' WHERE state = 'RUNNING'");
        return interrupted + idle;
    }

    /** Interrupts the matching running attempts, frees their cases and interrupts the runs they belong to. */
    private int interrupt(String attemptFilter, Instant now, Object... args) {
        return transactions.execute(status -> {
            var runIds = jdbc.queryForList(
                    "SELECT DISTINCT c.run_id FROM case_attempt a JOIN run_case c ON c.case_id = a.case_id" + " WHERE "
                            + attemptFilter,
                    UUID.class,
                    args);
            if (runIds.isEmpty()) {
                return 0;
            }
            jdbc.update(
                    "UPDATE run_case SET state = 'PENDING' WHERE state = 'RUNNING' AND case_id IN ("
                            + "SELECT a.case_id FROM case_attempt a WHERE " + attemptFilter + ")",
                    args);
            jdbc.update(
                    "UPDATE case_attempt a SET state = 'INTERRUPTED', finished_at = ?, lease_expires_at = NULL"
                            + " WHERE " + attemptFilter,
                    prepend(Rows.timestamp(now), args));
            runIds.forEach(runId ->
                    jdbc.update("UPDATE run SET state = 'INTERRUPTED' WHERE run_id = ? AND state = 'RUNNING'", runId));
            return runIds.size();
        });
    }

    private boolean settleAttempt(UUID attemptId, String owner, String state, Instant now, Failure failure) {
        return jdbc.update(
                        "UPDATE case_attempt SET state = ?, finished_at = ?, lease_expires_at = NULL,"
                                + " failure_code = ?, failure_message = ? WHERE attempt_id = ? AND lease_owner = ?"
                                + " AND state = 'RUNNING'",
                        state,
                        Rows.timestamp(now),
                        failure == null ? null : failure.code().name(),
                        failure == null ? null : failure.message(),
                        attemptId,
                        owner)
                == 1;
    }

    private static Object[] prepend(Object first, Object[] rest) {
        var all = new Object[rest.length + 1];
        all[0] = first;
        System.arraycopy(rest, 0, all, 1, rest.length);
        return all;
    }
}
