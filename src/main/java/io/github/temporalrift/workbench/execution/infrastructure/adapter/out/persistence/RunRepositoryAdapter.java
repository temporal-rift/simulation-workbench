package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCommand;
import io.github.temporalrift.workbench.execution.domain.run.RunCreation;
import io.github.temporalrift.workbench.execution.domain.run.RunState;

/** PostgreSQL storage of runs and their logical cases; state changes are compare-and-set. */
public class RunRepositoryAdapter implements RunRepository {

    private static final String CASE_COLUMNS =
            "case_id, run_id, case_key, ordinal, variant_label, seed, player_count, seats_json, state, result_json";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final Rows rows;

    public RunRepositoryAdapter(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rows = new Rows(objectMapper);
    }

    @Override
    public RunCreation create(UUID idempotencyKey, String requestHash, Run run, int concurrency, List<NewCase> cases) {
        var created = transactions.execute(status -> {
            jdbc.update(
                    "INSERT INTO run (run_id, experiment_id, state, concurrency, created_at) VALUES (?, ?, ?, ?, ?)",
                    run.runId(),
                    run.experimentId(),
                    run.state().name(),
                    concurrency,
                    Rows.timestamp(run.createdAt()));
            var claimed = jdbc.update(
                    "INSERT INTO run_command (idempotency_key, operation, run_id, request_hash, created_at)"
                            + " VALUES (?, 'startRun', ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                    idempotencyKey,
                    run.runId(),
                    requestHash,
                    Rows.timestamp(run.createdAt()));
            if (claimed == 0) {
                status.setRollbackOnly();
                return false;
            }
            jdbc.batchUpdate(
                    "INSERT INTO run_case (case_id, run_id, case_key, ordinal, variant_label, seed, player_count,"
                            + " seats_json, state) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')",
                    cases,
                    500,
                    (statement, newCase) -> {
                        statement.setObject(1, UUID.randomUUID());
                        statement.setObject(2, run.runId());
                        statement.setObject(3, newCase.caseKey());
                        statement.setInt(4, newCase.ordinal());
                        statement.setString(5, newCase.variantLabel());
                        statement.setString(6, newCase.seed());
                        statement.setInt(7, newCase.playerCount());
                        statement.setString(8, rows.json(newCase.seats()));
                    });
            return true;
        });
        if (Boolean.TRUE.equals(created)) {
            return new RunCreation.Created();
        }
        return new RunCreation.Existing(findCommand(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency claim vanished for " + idempotencyKey)));
    }

    @Override
    public Optional<Run> find(UUID runId) {
        return jdbc.query("SELECT * FROM run WHERE run_id = ?", (rs, i) -> rows.run(rs), runId).stream()
                .findFirst();
    }

    @Override
    public CaseCounts counts(UUID runId) {
        return countsOf(List.of(runId)).get(runId);
    }

    @Override
    public Map<UUID, CaseCounts> countsOf(Collection<UUID> runIds) {
        var perRun = new HashMap<UUID, int[]>();
        runIds.forEach(runId -> perRun.put(runId, new int[CaseState.values().length]));
        if (!runIds.isEmpty()) {
            var placeholders = String.join(",", Collections.nCopies(runIds.size(), "?"));
            jdbc.query(
                    "SELECT run_id, state, count(*) AS total FROM run_case WHERE run_id IN (" + placeholders
                            + ") GROUP BY run_id, state",
                    rs -> {
                        perRun.get(rs.getObject("run_id", UUID.class))[
                                        CaseState.valueOf(rs.getString("state")).ordinal()] =
                                rs.getInt("total");
                    },
                    runIds.toArray());
        }
        var result = new HashMap<UUID, CaseCounts>();
        perRun.forEach((runId, counts) -> result.put(runId, caseCounts(counts)));
        return result;
    }

    private static CaseCounts caseCounts(int[] counts) {
        int pending = counts[CaseState.PENDING.ordinal()];
        int running = counts[CaseState.RUNNING.ordinal()];
        int succeeded = counts[CaseState.SUCCEEDED.ordinal()];
        int failed = counts[CaseState.FAILED.ordinal()];
        int cancelled = counts[CaseState.CANCELLED.ordinal()];
        return new CaseCounts(
                pending + running + succeeded + failed + cancelled, pending, running, succeeded, failed, cancelled);
    }

    @Override
    public boolean update(RunState expected, Run next) {
        var failure = next.failure();
        return jdbc.update(
                        "UPDATE run SET state = ?, started_at = ?, finished_at = ?, failure_code = ?,"
                                + " failure_message = ? WHERE run_id = ? AND state = ?",
                        next.state().name(),
                        Rows.timestamp(next.startedAt()),
                        Rows.timestamp(next.finishedAt()),
                        failure == null ? null : failure.code().name(),
                        failure == null ? null : failure.message(),
                        next.runId(),
                        expected.name())
                == 1;
    }

    @Override
    public List<Run> findByState(RunState state) {
        return jdbc.query(
                "SELECT * FROM run WHERE state = ? ORDER BY created_at", (rs, i) -> rows.run(rs), state.name());
    }

    @Override
    public List<Run> list(UUID experimentId, RunState state, int limit, int offset) {
        var filter = new Filter()
                .and("experiment_id = ?", experimentId)
                .and("state = ?", state == null ? null : state.name());
        return jdbc.query(
                "SELECT * FROM run" + filter.where() + " ORDER BY created_at DESC, run_id DESC LIMIT ? OFFSET ?",
                (rs, i) -> rows.run(rs),
                filter.with(limit, offset));
    }

    @Override
    public long countRuns(UUID experimentId, RunState state) {
        var filter = new Filter()
                .and("experiment_id = ?", experimentId)
                .and("state = ?", state == null ? null : state.name());
        return count("SELECT count(*) FROM run" + filter.where(), filter.arguments());
    }

    @Override
    public int cancelPendingCases(UUID runId) {
        return jdbc.update("UPDATE run_case SET state = 'CANCELLED' WHERE run_id = ? AND state = 'PENDING'", runId);
    }

    @Override
    public Optional<LogicalCase> findCase(UUID runId, UUID caseId) {
        return jdbc
                .query(
                        "SELECT " + CASE_COLUMNS + " FROM run_case WHERE run_id = ? AND case_id = ?",
                        (rs, i) -> rows.logicalCase(rs),
                        runId,
                        caseId)
                .stream()
                .findFirst();
    }

    @Override
    public List<LogicalCase> cases(UUID runId) {
        return jdbc.query(
                "SELECT " + CASE_COLUMNS + " FROM run_case WHERE run_id = ? ORDER BY ordinal",
                (rs, i) -> rows.logicalCase(rs),
                runId);
    }

    @Override
    public List<LogicalCase> listCases(UUID runId, CaseState state, String variantLabel, int limit, int offset) {
        var filter = caseFilter(runId, state, variantLabel);
        return jdbc.query(
                "SELECT " + CASE_COLUMNS + " FROM run_case" + filter.where() + " ORDER BY ordinal LIMIT ? OFFSET ?",
                (rs, i) -> rows.logicalCase(rs),
                filter.with(limit, offset));
    }

    @Override
    public long countCases(UUID runId, CaseState state, String variantLabel) {
        var filter = caseFilter(runId, state, variantLabel);
        return count("SELECT count(*) FROM run_case" + filter.where(), filter.arguments());
    }

    private static Filter caseFilter(UUID runId, CaseState state, String variantLabel) {
        return new Filter()
                .and("run_id = ?", runId)
                .and("state = ?", state == null ? null : state.name())
                .and("variant_label = ?", variantLabel);
    }

    private long count(String sql, Object[] arguments) {
        var total = jdbc.queryForObject(sql, Long.class, arguments);
        return total == null ? 0 : total;
    }

    /** A conjunction of optional equality conditions; a null value leaves its condition out. */
    private static final class Filter {

        private final List<String> conditions = new ArrayList<>();
        private final List<Object> values = new ArrayList<>();

        Filter and(String condition, Object value) {
            if (value != null) {
                conditions.add(condition);
                values.add(value);
            }
            return this;
        }

        String where() {
            return conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions);
        }

        Object[] arguments() {
            return values.toArray();
        }

        Object[] with(Object... more) {
            var all = new ArrayList<>(values);
            all.addAll(List.of(more));
            return all.toArray();
        }
    }

    @Override
    public List<Attempt> attemptsOf(UUID caseId) {
        return jdbc.query(
                "SELECT * FROM case_attempt WHERE case_id = ? ORDER BY ordinal", (rs, i) -> rows.attempt(rs), caseId);
    }

    @Override
    public Optional<RunCommand> findCommand(UUID idempotencyKey) {
        return jdbc
                .query(
                        "SELECT idempotency_key, operation, run_id, request_hash FROM run_command"
                                + " WHERE idempotency_key = ?",
                        (rs, i) -> new RunCommand(
                                rs.getObject("idempotency_key", UUID.class),
                                rs.getString("operation"),
                                rs.getObject("run_id", UUID.class),
                                rs.getString("request_hash")),
                        idempotencyKey)
                .stream()
                .findFirst();
    }

    @Override
    public boolean claimCommand(UUID idempotencyKey, String operation, UUID runId, String requestHash, Instant now) {
        return jdbc.update(
                        "INSERT INTO run_command (idempotency_key, operation, run_id, request_hash, created_at)"
                                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT (idempotency_key) DO NOTHING",
                        idempotencyKey,
                        operation,
                        runId,
                        requestHash,
                        Rows.timestamp(now))
                == 1;
    }
}
