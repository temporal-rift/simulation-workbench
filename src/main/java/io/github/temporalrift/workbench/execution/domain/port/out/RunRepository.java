package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCommand;
import io.github.temporalrift.workbench.execution.domain.run.RunCreation;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/** Durable run state: runs, their logical cases and the idempotency claims of run commands. */
public interface RunRepository {

    /**
     * Stores the run with all its cases and claims the idempotency key in one transaction. When the key
     * is already claimed nothing is stored and the existing claim is returned.
     *
     * @param concurrency how many of the run's cases may execute at once
     */
    RunCreation create(UUID idempotencyKey, String requestHash, Run run, int concurrency, List<NewCase> cases);

    Optional<Run> find(UUID runId);

    /** Totals of the run's logical cases, counting each case once. */
    CaseCounts counts(UUID runId);

    /** Applies {@code next} only if the stored state is still {@code expected}; returns whether it applied. */
    boolean update(RunState expected, Run next);

    List<Run> findByState(RunState state);

    /** Marks every pending case of the run cancelled and returns how many were. */
    int cancelPendingCases(UUID runId);

    Optional<LogicalCase> findCase(UUID runId, UUID caseId);

    List<Attempt> attemptsOf(UUID caseId);

    Optional<RunCommand> findCommand(UUID idempotencyKey);

    /** Claims the key for a cancel or resume; returns false when it was already claimed. */
    boolean claimCommand(UUID idempotencyKey, String operation, UUID runId, String requestHash, Instant now);

    /** A case to schedule, in matrix order. */
    record NewCase(UUID caseKey, int ordinal, String variantLabel, String seed, int playerCount, List<SeatPlan> seats) {
        public NewCase {
            seats = List.copyOf(seats);
        }
    }
}
