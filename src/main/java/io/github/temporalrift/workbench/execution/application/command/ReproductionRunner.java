package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.TranscriptDivergedException;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

/**
 * Executes queued reproductions on clean lanes. Like a research case, a reproduction runs under a renewable
 * lease and only the lease owner settles it. It reports {@code MATCH} when the semantic setup, decisions,
 * outcomes, scoring and result equal the saved case, otherwise {@code DIVERGED} with the first difference,
 * and {@code FAILED} when it could not run to an ending at all.
 */
public class ReproductionRunner implements RunReproductionUseCase {

    private final ReproductionRepository reproductions;
    private final ReproductionSources sources;
    private final EvidenceLedger evidence;
    private final LaneProvider lanes;
    private final CaseDriver driver;
    private final Clock clock;
    private final ExecutionSettings settings;

    public ReproductionRunner(
            ReproductionRepository reproductions,
            ReproductionSources sources,
            LaneProvider lanes,
            CaseDriver driver,
            Clock clock,
            ExecutionSettings settings) {
        this.reproductions = reproductions;
        this.sources = sources;
        this.evidence = sources.evidence();
        this.lanes = lanes;
        this.driver = driver;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public void recoverAfterRestart() {
        reproductions.requeueAllRunning();
    }

    @Override
    public void maintain() {
        reproductions.requeueExpired(clock.instant());
    }

    @Override
    public boolean runNext(String owner) {
        if (!lanes.hasFreeLane()) {
            return false;
        }
        var now = clock.instant();
        var claimed = reproductions.claimNext(owner, now, now.plus(settings.lease()), lanes.freeLaneIds());
        if (claimed.isEmpty()) {
            return false;
        }
        var reproduction = claimed.get();
        ReproductionInputs inputs;
        try {
            inputs = ReproductionInputs.verify(reproduction.runId(), reproduction.caseId(), sources);
        } catch (ManifestMismatchException | RunNotFoundException e) {
            var failure = new Failure(FailureCode.CONTRACT_MISMATCH, e.getMessage());
            settle(reproduction, owner, reproduction.failed(failure, now));
            return true;
        }
        var lane = lanes.acquireExactly(reproduction.laneId());
        if (lane.isEmpty()) {
            reproductions.release(reproduction.reproductionId(), owner);
            return false;
        }
        execute(reproduction, inputs, owner, lane.get());
        return true;
    }

    @Override
    public void shutdown(String owner) {
        reproductions.requeueOwnedBy(owner);
    }

    private void execute(Reproduction reproduction, ReproductionInputs inputs, String owner, CaseLane lane) {
        var scope = reproduction.reproductionId();
        try (lane) {
            evidence.purge(scope);
            var replayed = driver.replay(
                    inputs.logicalCase(),
                    scope,
                    reproduction.attemptId(),
                    inputs.transcript(),
                    inputs.plan(),
                    lane,
                    guard(scope, owner));
            var divergence = DivergenceFinder.first(
                    evidence.steps(inputs.logicalCase().caseId(), inputs.originalGameId()),
                    evidence.steps(scope, replayed.gameId()),
                    inputs.logicalCase().result(),
                    replayed.result());
            var now = clock.instant();
            settle(
                    reproduction,
                    owner,
                    divergence
                            .map(found -> reproduction.diverged(found, now))
                            .orElseGet(() -> reproduction.matched(now)));
        } catch (TranscriptDivergedException e) {
            settle(reproduction, owner, reproduction.diverged(e.divergence(), clock.instant()));
        } catch (AttemptFailedException e) {
            settle(reproduction, owner, reproduction.failed(e.failure(), clock.instant()));
        } catch (LeaseLostException _) {
            // Another worker owns the reproduction now; nothing this worker holds is left to settle.
        } catch (RuntimeException e) {
            var failure = new Failure(FailureCode.EXECUTION_FAILED, describe(e));
            settle(reproduction, owner, reproduction.failed(failure, clock.instant()));
        }
    }

    private void settle(Reproduction reproduction, String owner, Reproduction settled) {
        reproductions.settle(reproduction.reproductionId(), owner, settled);
    }

    private AttemptGuard guard(UUID reproductionId, String owner) {
        return new AttemptGuard() {
            private Instant lastBeat = clock.instant();

            @Override
            public void checkpoint() {
                var now = clock.instant();
                if (now.isBefore(lastBeat.plus(settings.heartbeat()))) {
                    return;
                }
                lastBeat = now;
                if (!reproductions.extendLease(reproductionId, owner, now.plus(settings.lease()))) {
                    throw new LeaseLostException();
                }
            }
        };
    }

    private static String describe(RuntimeException e) {
        var message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }
}
