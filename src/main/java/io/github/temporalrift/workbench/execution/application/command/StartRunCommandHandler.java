package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.util.UUID;
import java.util.stream.IntStream;

import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCommand;
import io.github.temporalrift.workbench.execution.domain.run.RunCreation;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

/**
 * Creates a queued run with one logical case per matrix coordinate. The same idempotency key returns
 * the original run; a key reused for another experiment conflicts.
 */
public class StartRunCommandHandler implements StartRunUseCase {

    static final String OPERATION = "startRun";

    private final ExperimentSource experiments;
    private final RunRepository runs;
    private final Clock clock;

    public StartRunCommandHandler(ExperimentSource experiments, RunRepository runs, Clock clock) {
        this.experiments = experiments;
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    public RunView handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var requestHash = RunRequestHash.of(OPERATION, command.experimentId());
        var replay = runs.findCommand(command.idempotencyKey());
        if (replay.isPresent()) {
            return replayed(replay.get(), requestHash);
        }
        var plan = experiments
                .plan(command.experimentId())
                .orElseThrow(() -> new RunNotFoundException("Experiment", command.experimentId()));
        var planned = experiments
                .cases(command.experimentId())
                .orElseThrow(() -> new RunNotFoundException("Experiment", command.experimentId()));
        var cases = IntStream.range(0, planned.size())
                .mapToObj(ordinal -> {
                    var coordinate = planned.get(ordinal);
                    return new RunRepository.NewCase(
                            coordinate.caseKey(),
                            ordinal,
                            coordinate.variantLabel(),
                            coordinate.seed(),
                            coordinate.playerCount(),
                            coordinate.seats());
                })
                .toList();
        var run = Run.queued(UUID.randomUUID(), command.experimentId(), clock.instant());
        return switch (runs.create(command.idempotencyKey(), requestHash, run, plan.concurrency(), cases)) {
            case RunCreation.Created _ -> view(run.runId());
            case RunCreation.Existing(var claim) -> replayed(claim, requestHash);
        };
    }

    private RunView replayed(RunCommand claim, String requestHash) {
        if (!OPERATION.equals(claim.operation()) || !claim.requestHash().equals(requestHash)) {
            throw new RunIdempotencyConflictException();
        }
        return view(claim.runId());
    }

    private RunView view(UUID runId) {
        var run = runs.find(runId).orElseThrow(() -> new RunNotFoundException("Run", runId));
        return new RunView(run, runs.counts(runId));
    }
}
