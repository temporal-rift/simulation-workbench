package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCommand;
import io.github.temporalrift.workbench.execution.domain.run.RunCreation;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/** Storage of runs and their logical cases; state changes are compare-and-set under the run's row lock. */
@Component
public class RunRepositoryAdapter implements RunRepository {

    private final RunJpaRepository runs;
    private final RunCaseJpaRepository cases;
    private final CaseAttemptJpaRepository attempts;
    private final RunCommandJpaRepository commands;
    private final RunProgressJpaRepository progress;
    private final RunInserter inserter;
    private final InsertOnce insertOnce;
    private final RunListingQueries listing;
    private final Mappings mappings;

    RunRepositoryAdapter(
            RunJpaRepository runs,
            RunCaseJpaRepository cases,
            CaseAttemptJpaRepository attempts,
            RunCommandJpaRepository commands,
            RunProgressJpaRepository progress,
            RunInserter inserter,
            InsertOnce insertOnce,
            RunListingQueries listing,
            Mappings mappings) {
        this.runs = runs;
        this.cases = cases;
        this.attempts = attempts;
        this.commands = commands;
        this.progress = progress;
        this.inserter = inserter;
        this.insertOnce = insertOnce;
        this.listing = listing;
        this.mappings = mappings;
    }

    @Override
    public RunCreation create(
            UUID idempotencyKey, String requestHash, Run run, int concurrency, List<NewCase> newCases) {
        if (commands.existsById(idempotencyKey)) {
            return existing(idempotencyKey);
        }
        try {
            inserter.insert(idempotencyKey, requestHash, run, concurrency, newCases);
            return new RunCreation.Created();
        } catch (DataIntegrityViolationException claimedMeanwhile) {
            return existing(idempotencyKey);
        }
    }

    private RunCreation existing(UUID idempotencyKey) {
        return new RunCreation.Existing(findCommand(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException("Idempotency claim vanished for " + idempotencyKey)));
    }

    @Override
    public Optional<Run> find(UUID runId) {
        return runs.findById(runId).map(mappings::run);
    }

    @Override
    public CaseCounts counts(UUID runId) {
        return countsOf(List.of(runId)).get(runId);
    }

    @Override
    public Map<UUID, CaseCounts> countsOf(Collection<UUID> runIds) {
        var counts = new HashMap<UUID, CaseCounts>();
        runIds.forEach(runId -> counts.put(runId, new CaseCounts(0, 0, 0, 0, 0, 0)));
        progress.findAllByRunIdIn(runIds)
                .forEach(row -> counts.put(
                        row.runId(),
                        new CaseCounts(
                                Math.toIntExact(row.pending()
                                        + row.running()
                                        + row.succeeded()
                                        + row.failed()
                                        + row.cancelled()),
                                Math.toIntExact(row.pending()),
                                Math.toIntExact(row.running()),
                                Math.toIntExact(row.succeeded()),
                                Math.toIntExact(row.failed()),
                                Math.toIntExact(row.cancelled()))));
        return counts;
    }

    @Override
    @Transactional
    public boolean update(RunState expected, Run next) {
        var failure = next.failure();
        return runs.findWithLockByRunIdAndState(next.runId(), expected.name())
                .map(run -> {
                    run.transition(
                            next.state().name(),
                            next.startedAt(),
                            next.finishedAt(),
                            Mappings.failureCode(failure),
                            Mappings.failureMessage(failure));
                    return true;
                })
                .orElse(false);
    }

    @Override
    public List<Run> findByState(RunState state) {
        return runs.findAllByStateOrderByCreatedAt(state.name()).stream()
                .map(mappings::run)
                .toList();
    }

    @Override
    public List<Run> list(UUID experimentId, RunState state, int limit, int offset) {
        var ids = listing.runIds(experimentId, state == null ? null : state.name(), limit, offset);
        return inOrder(ids, runs.findAllById(ids), RunJpaEntity::runId).stream()
                .map(mappings::run)
                .toList();
    }

    @Override
    public long countRuns(UUID experimentId, RunState state) {
        return listing.runCount(experimentId, state == null ? null : state.name());
    }

    @Override
    @Transactional
    public int cancelPendingCases(UUID runId) {
        return cases.cancelPendingCases(runId);
    }

    @Override
    public Optional<LogicalCase> findCase(UUID runId, UUID caseId) {
        return cases.findByRunIdAndCaseId(runId, caseId).map(mappings::logicalCase);
    }

    @Override
    public List<LogicalCase> cases(UUID runId) {
        return cases.findAllByRunIdOrderByOrdinal(runId).stream()
                .map(mappings::logicalCase)
                .toList();
    }

    @Override
    public List<LogicalCase> listCases(UUID runId, CaseState state, String variantLabel, int limit, int offset) {
        var ids = listing.caseIds(runId, state == null ? null : state.name(), variantLabel, limit, offset);
        return inOrder(ids, cases.findAllById(ids), RunCaseJpaEntity::caseId).stream()
                .map(mappings::logicalCase)
                .toList();
    }

    @Override
    public long countCases(UUID runId, CaseState state, String variantLabel) {
        return listing.caseCount(runId, state == null ? null : state.name(), variantLabel);
    }

    @Override
    public List<Attempt> attemptsOf(UUID caseId) {
        return attempts.findAllByCaseIdOrderByOrdinal(caseId).stream()
                .map(mappings::attempt)
                .toList();
    }

    @Override
    public Optional<RunCommand> findCommand(UUID idempotencyKey) {
        return commands.findById(idempotencyKey)
                .map(command -> new RunCommand(
                        command.idempotencyKey(), command.operation(), command.runId(), command.requestHash()));
    }

    @Override
    public boolean claimCommand(UUID idempotencyKey, String operation, UUID runId, String requestHash, Instant now) {
        return insertOnce.insert(() ->
                commands.saveAndFlush(new RunCommandJpaEntity(idempotencyKey, operation, runId, requestHash, now)));
    }

    /** Puts the loaded rows in the order the page query chose them. */
    private static <T> List<T> inOrder(List<UUID> ids, List<T> rows, Function<T, UUID> id) {
        var byId = rows.stream().collect(Collectors.toMap(id, Function.identity()));
        return ids.stream().map(byId::get).toList();
    }
}
