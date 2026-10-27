package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;

import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseOnLaneUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.LaneNotEquivalentException;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.run.BuildEquivalence;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;

/**
 * Queues an exact reproduction of a saved case on a lane the workbench names, after checking the case's pinned
 * artifacts and that the lane is build-equivalent to the lane that played the case, for the case's seats. The lane
 * that played the case stands for the case's environment, so it must still be configured and still fit the case. The
 * reproduction records the target lane's own fit. The same idempotency key returns the original reproduction; a key
 * reused for another case or lane conflicts.
 */
public class ReproduceCaseOnLaneCommandHandler implements ReproduceCaseOnLaneUseCase {

    static final String OPERATION = "reproduceCaseOnLane";

    private final ReproductionSources sources;
    private final ReproductionRepository reproductions;
    private final LaneProvider lanes;
    private final Clock clock;

    public ReproduceCaseOnLaneCommandHandler(
            ReproductionSources sources, ReproductionRepository reproductions, LaneProvider lanes, Clock clock) {
        this.sources = sources;
        this.reproductions = reproductions;
        this.lanes = lanes;
        this.clock = clock;
    }

    @Override
    public Reproduction handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var requestHash = RunRequestHash.of(OPERATION, command.caseId() + "@" + command.laneId());
        var existing = reproductions.findByKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayed(existing.get(), requestHash);
        }
        var inputs = ReproductionInputs.verify(command.runId(), command.caseId(), sources);
        var targetFit = equivalentTarget(command, inputs);
        var queued =
                Reproduction.queued(command.runId(), command.caseId(), command.laneId(), targetFit, clock.instant());
        return switch (reproductions.create(command.idempotencyKey(), requestHash, queued)) {
            case ReproductionCreation.Created _ -> queued;
            case ReproductionCreation.Existing(var claim) -> replayed(claim, requestHash);
        };
    }

    private LaneFit equivalentTarget(Command command, ReproductionInputs inputs) {
        var profiles = lanes.profiles();
        var target = profiles.get(command.laneId());
        if (target == null) {
            throw new LaneNotEquivalentException("Lane " + command.laneId() + " is not configured");
        }
        var played = profiles.get(inputs.originalLaneId());
        var logicalCase = inputs.logicalCase();
        if (played == null || !played.fit().equals(logicalCase.fit())) {
            throw new LaneNotEquivalentException("The lane that played the case, " + inputs.originalLaneId()
                    + ", is no longer configured as it was, so the case's environment cannot be compared");
        }
        BuildEquivalence.firstDifference(played, target, logicalCase.playerCount())
                .ifPresent(difference -> {
                    throw new LaneNotEquivalentException("Lane " + command.laneId() + " differs from lane "
                            + inputs.originalLaneId() + " in its " + difference);
                });
        return target.fit();
    }

    private static Reproduction replayed(ReproductionClaim claim, String requestHash) {
        if (!claim.requestHash().equals(requestHash)) {
            throw new RunIdempotencyConflictException();
        }
        return claim.reproduction();
    }
}
