package io.github.temporalrift.workbench.execution.application.query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.domain.evidence.InvalidReplayPerspectiveException;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.CountingGame;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

/**
 * Reads the evidence of the game that counts for a case: the succeeded attempt's game, or the latest game
 * the case has played while it is unfinished. A PLAYER replay is cut from one seat's own retained steps, which
 * hold only what that seat was entitled to see, so nothing another seat holds can reach it. An OBSERVER
 * replay adds every seat and the raw events, listed per source in offset order because no order across
 * sources exists.
 */
public class GetCaseReplayQueryHandler implements GetCaseReplayUseCase {

    private final RunRepository runs;
    private final ExperimentSource experiments;
    private final EvidenceLedger evidence;

    public GetCaseReplayQueryHandler(RunRepository runs, ExperimentSource experiments, EvidenceLedger evidence) {
        this.runs = runs;
        this.experiments = experiments;
        this.evidence = evidence;
    }

    @Override
    public ReplayPage handle(Query query) {
        var logicalCase = runs.findCase(query.runId(), query.caseId())
                .orElseThrow(() -> new RunNotFoundException("Case", query.caseId()));
        requireFittingSeat(query, logicalCase.seats().size());
        var run = runs.find(query.runId()).orElseThrow(() -> new RunNotFoundException("Run", query.runId()));
        var manifestDigest = experiments
                .plan(run.experimentId())
                .map(ExperimentSource.Plan::manifestDigest)
                .orElseThrow(() -> new RunNotFoundException("Experiment", run.experimentId()));
        var entries = CountingGame.of(runs.attemptsOf(query.caseId()))
                .map(gameId -> entries(query, gameId))
                .orElse(List.of());
        var after = query.afterStep() == null ? -1 : query.afterStep();
        var remaining = entries.stream().filter(entry -> entry.step() > after).toList();
        var page = remaining.stream().limit(query.limit()).toList();
        var next = remaining.size() > page.size() ? page.getLast().step() : null;
        return new ReplayPage(query.caseId(), manifestDigest, query.perspective(), page, next);
    }

    private static void requireFittingSeat(Query query, int seatCount) {
        var seat = query.seatIndex();
        if (query.perspective() == Perspective.OBSERVER) {
            if (seat != null) {
                throw new InvalidReplayPerspectiveException("An OBSERVER replay follows no seat");
            }
            return;
        }
        if (seat == null) {
            throw new InvalidReplayPerspectiveException("A PLAYER replay needs a seatIndex");
        }
        if (seat < 0 || seat >= seatCount) {
            throw new InvalidReplayPerspectiveException("Seat " + seat + " does not exist in this case");
        }
    }

    private List<Entry> entries(Query query, UUID gameId) {
        var entries = new ArrayList<Entry>();
        var steps = evidence.steps(query.caseId(), gameId);
        steps.stream()
                .filter(step -> query.perspective() == Perspective.OBSERVER || step.seatIndex() == query.seatIndex())
                .forEach(step -> entries.add(new Entry.Command(step.step(), step)));
        if (query.perspective() == Perspective.OBSERVER) {
            var next = steps.isEmpty() ? 0 : steps.getLast().step() + 1;
            for (var event : evidence.events(query.caseId(), gameId)) {
                entries.add(new Entry.Event(next++, event));
            }
        }
        return entries;
    }
}
