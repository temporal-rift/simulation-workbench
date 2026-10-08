package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;

/**
 * Durable scheduling of logical cases: a pending case is a job, an attempt holds its lease, and every
 * outcome is fenced by the lease owner so a stale worker can never write over a recovered case.
 */
public interface CaseLedger {

    /**
     * Atomically claims the next pending case of a running run that is within its concurrency bound and
     * opens a new attempt for it, or returns empty when there is nothing to do.
     */
    Optional<Claim> claimNext(String owner, Instant now, Instant leaseUntil);

    /** Extends the lease; false means the attempt is no longer this owner's to run. */
    boolean extendLease(UUID attemptId, String owner, Instant leaseUntil);

    /** Records the game and lane an attempt plays on, so a recovering attempt can reconcile against them. */
    void recordGame(UUID attemptId, UUID gameId, String laneId);

    /** Marks the attempt and its case succeeded with the result; false when the lease was lost. */
    boolean succeed(UUID attemptId, String owner, CaseResult result, Instant now);

    /**
     * Marks the attempt failed. The case returns to pending when {@code retry} is set, otherwise it fails.
     */
    FailOutcome fail(UUID attemptId, String owner, Failure failure, boolean retry, Instant now);

    /** Marks the attempt and its case cancelled; false when the lease was lost. */
    boolean cancel(UUID attemptId, String owner, Instant now);

    /** Gives a claim back without a result when no lane could be acquired; the case returns to pending. */
    void release(UUID attemptId, String owner, Instant now);

    /** Interrupts attempts whose lease expired, returning their cases to pending and their runs to interrupted. */
    int interruptExpired(Instant now);

    /** Interrupts the attempts this owner holds, as on a graceful shutdown. */
    int interruptOwnedBy(String owner, Instant now);

    /** Interrupts every running run and attempt left by a previous process. */
    int interruptAllRunning(Instant now);

    /** A claimed case with its new attempt and the attempt it recovers, if any. */
    record Claim(LogicalCase logicalCase, Attempt attempt, Optional<Attempt> previous, int concurrency) {}

    enum FailOutcome {
        /** The case went back to pending for a fresh attempt. */
        RETRY,
        /** The case is failed. */
        CASE_FAILED,
        /** The lease was lost; nothing changed. */
        LOST
    }
}
