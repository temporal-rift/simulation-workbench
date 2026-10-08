package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;

import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;

/**
 * Queues an exact reproduction of a saved case after checking its pinned artifacts. The reproduction is
 * its own attempt with its own identifiers; the case keeps the single result it already has. The same
 * idempotency key returns the original reproduction; a key reused for another case conflicts.
 */
public class ReproduceCaseCommandHandler implements ReproduceCaseUseCase {

    static final String OPERATION = "reproduceCase";

    private final RunRepository runs;
    private final ExperimentSource experiments;
    private final EvidenceLedger evidence;
    private final ReproductionRepository reproductions;
    private final Clock clock;

    public ReproduceCaseCommandHandler(
            RunRepository runs,
            ExperimentSource experiments,
            EvidenceLedger evidence,
            ReproductionRepository reproductions,
            Clock clock) {
        this.runs = runs;
        this.experiments = experiments;
        this.evidence = evidence;
        this.reproductions = reproductions;
        this.clock = clock;
    }

    @Override
    public Reproduction handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var requestHash = RunRequestHash.of(OPERATION, command.caseId());
        var existing = reproductions.findByKey(command.idempotencyKey());
        if (existing.isPresent()) {
            return replayed(existing.get(), requestHash);
        }
        ReproductionInputs.verify(command.runId(), command.caseId(), runs, experiments, evidence);
        var queued = Reproduction.queued(command.runId(), command.caseId(), clock.instant());
        return switch (reproductions.create(command.idempotencyKey(), requestHash, queued)) {
            case ReproductionCreation.Created _ -> queued;
            case ReproductionCreation.Existing(var claim) -> replayed(claim, requestHash);
        };
    }

    private Reproduction replayed(ReproductionClaim claim, String requestHash) {
        if (!claim.requestHash().equals(requestHash)) {
            throw new RunIdempotencyConflictException();
        }
        return claim.reproduction();
    }
}
