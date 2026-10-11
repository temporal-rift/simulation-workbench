package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.Failure;

/**
 * Scheduling of logical cases. A run's row lock serializes the claims on it, so its concurrency bound holds
 * across workers; every outcome is fenced by the attempt's running state and lease owner under the attempt's row
 * lock, so a worker whose attempt was interrupted or recovered can no longer write a result.
 */
@Component
public class CaseLedgerAdapter implements CaseLedger {

    private static final String PENDING = "PENDING";
    private static final String RUNNING = "RUNNING";
    private static final String INTERRUPTED = "INTERRUPTED";

    private final RunJpaRepository runs;
    private final RunCaseJpaRepository cases;
    private final CaseAttemptJpaRepository attempts;
    private final Mappings mappings;
    private final StoredJson json;

    CaseLedgerAdapter(
            RunJpaRepository runs,
            RunCaseJpaRepository cases,
            CaseAttemptJpaRepository attempts,
            Mappings mappings,
            StoredJson json) {
        this.runs = runs;
        this.cases = cases;
        this.attempts = attempts;
        this.mappings = mappings;
        this.json = json;
    }

    @Override
    @Transactional
    public Optional<Claim> claimNext(String owner, Instant now, Instant leaseUntil) {
        for (var run : runs.findAllByStateOrderByCreatedAt(RUNNING)) {
            var claim = claimFromRun(run.runId(), owner, now, leaseUntil);
            if (claim.isPresent()) {
                return claim;
            }
        }
        return Optional.empty();
    }

    private Optional<Claim> claimFromRun(UUID runId, String owner, Instant now, Instant leaseUntil) {
        return runs.findWithLockByRunIdAndState(runId, RUNNING).flatMap(run -> {
            if (cases.countByRunIdAndState(runId, RUNNING) >= run.concurrency()) {
                return Optional.empty();
            }
            return cases.findFirstByRunIdAndStateOrderByOrdinal(runId, PENDING)
                    .map(next -> open(next, run.concurrency(), owner, now, leaseUntil));
        });
    }

    private Claim open(RunCaseJpaEntity next, int concurrency, String owner, Instant now, Instant leaseUntil) {
        next.settle(RUNNING, null);
        var previous = attempts.findFirstByCaseIdOrderByOrdinalDesc(next.caseId());
        var ordinal = previous.map(CaseAttemptJpaEntity::ordinal).orElse(0) + 1;
        var attempt = attempts.save(
                new CaseAttemptJpaEntity(UUID.randomUUID(), next.caseId(), ordinal, RUNNING, owner, leaseUntil, now));
        return new Claim(
                mappings.logicalCase(next), mappings.attempt(attempt), previous.map(mappings::attempt), concurrency);
    }

    @Override
    @Transactional
    public boolean extendLease(UUID attemptId, String owner, Instant leaseUntil) {
        return attempts.findWithLockByAttemptIdAndLeaseOwnerAndState(attemptId, owner, RUNNING)
                .map(attempt -> {
                    attempt.extendLease(leaseUntil);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public void recordGame(UUID attemptId, UUID gameId, String laneId) {
        attempts.findById(attemptId).ifPresent(attempt -> attempt.play(gameId, laneId));
    }

    @Override
    @Transactional
    public boolean succeed(UUID attemptId, String owner, CaseResult result, Instant now) {
        return settle(attemptId, owner, "SUCCEEDED", now, null, "SUCCEEDED", json.write(result));
    }

    @Override
    @Transactional
    public FailOutcome fail(UUID attemptId, String owner, Failure failure, boolean retry, Instant now) {
        if (!settle(attemptId, owner, "FAILED", now, failure, retry ? PENDING : "FAILED", null)) {
            return FailOutcome.LOST;
        }
        return retry ? FailOutcome.RETRY : FailOutcome.CASE_FAILED;
    }

    @Override
    @Transactional
    public boolean cancel(UUID attemptId, String owner, Instant now) {
        return settle(attemptId, owner, "CANCELLED", now, null, "CANCELLED", null);
    }

    @Override
    @Transactional
    public void release(UUID attemptId, String owner, Instant now) {
        attempts.findWithLockByAttemptIdAndLeaseOwnerAndState(attemptId, owner, RUNNING)
                .ifPresent(attempt -> {
                    attempts.delete(attempt);
                    cases.findWithLockByCaseIdAndState(attempt.caseId(), RUNNING)
                            .ifPresent(logicalCase -> logicalCase.settle(PENDING, null));
                });
    }

    @Override
    @Transactional
    public int interruptExpired(Instant now) {
        return interrupt(
                attempts.findAllByStateAndLeaseExpiresAtBefore(RUNNING, now).stream()
                        .map(CaseAttemptJpaEntity::attemptId)
                        .toList(),
                now);
    }

    @Override
    @Transactional
    public int interruptOwnedBy(String owner, Instant now) {
        return interrupt(
                attempts.findAllByStateAndLeaseOwner(RUNNING, owner).stream()
                        .map(CaseAttemptJpaEntity::attemptId)
                        .toList(),
                now);
    }

    @Override
    @Transactional
    public int interruptAllRunning(Instant now) {
        var interrupted = interrupt(
                attempts.findAllByState(RUNNING).stream()
                        .map(CaseAttemptJpaEntity::attemptId)
                        .toList(),
                now);
        var idle = runs.findAllByStateOrderByCreatedAt(RUNNING);
        idle.forEach(run -> run.changeState(INTERRUPTED));
        return interrupted + idle.size();
    }

    /**
     * Interrupts the given attempts that are still running, frees their cases and interrupts the runs they
     * belong to. Each attempt is re-read under its lock as it is settled, so one that finished meanwhile is left
     * alone.
     */
    private int interrupt(List<UUID> attemptIds, Instant now) {
        var interruptedRuns = new HashSet<UUID>();
        for (var attemptId : attemptIds) {
            attempts.findWithLockByAttemptIdAndState(attemptId, RUNNING).ifPresent(attempt -> {
                attempt.settle(INTERRUPTED, now, null, null);
                cases.findWithLockByCaseIdAndState(attempt.caseId(), RUNNING).ifPresent(logicalCase -> {
                    logicalCase.settle(PENDING, null);
                    interruptedRuns.add(logicalCase.runId());
                });
            });
        }
        interruptedRuns.forEach(runId ->
                runs.findWithLockByRunIdAndState(runId, RUNNING).ifPresent(run -> run.changeState(INTERRUPTED)));
        return interruptedRuns.size();
    }

    /** Settles the attempt if it is still this owner's, then the case it plays if that is still running. */
    private boolean settle(
            UUID attemptId,
            String owner,
            String attemptState,
            Instant now,
            Failure failure,
            String caseState,
            String resultJson) {
        var attempt = attempts.findWithLockByAttemptIdAndLeaseOwnerAndState(attemptId, owner, RUNNING);
        if (attempt.isEmpty()) {
            return false;
        }
        attempt.get().settle(attemptState, now, Mappings.failureCode(failure), Mappings.failureMessage(failure));
        cases.findWithLockByCaseIdAndState(attempt.get().caseId(), RUNNING)
                .ifPresent(logicalCase -> logicalCase.settle(caseState, resultJson));
        return true;
    }
}
