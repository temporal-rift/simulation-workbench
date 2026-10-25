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
        var manifestDigest = evidence.manifestDigest(query.caseId())
                .or(() -> experiments.plan(run.experimentId()).map(ExperimentSource.Plan::manifestDigest))
                .orElseThrow(() -> new RunNotFoundException("Experiment", run.experimentId()));
        return CountingGame.of(runs.attemptsOf(query.caseId()))
                .map(gameId -> page(query, gameId, manifestDigest))
                .orElseGet(() -> new ReplayPage(query.caseId(), manifestDigest, query.perspective(), List.of(), null));
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

    private ReplayPage page(Query query, UUID gameId, String manifestDigest) {
        var after = query.afterStep() == null ? -1 : query.afterStep();
        var observer = query.perspective() == Perspective.OBSERVER;
        var limit = query.limit();
        var steps = evidence.stepsPage(query.caseId(), gameId, observer ? null : query.seatIndex(), after, limit + 1);
        var entries = new ArrayList<Entry>();
        steps.stream().limit(limit).forEach(step -> entries.add(new Entry.Command(step.step(), step)));
        var more = steps.size() > limit;
        if (observer && !more) {
            var lastStep = evidence.lastStep(query.caseId(), gameId).orElse(-1);
            var skipped = Math.clamp((long) after - lastStep, 0, Integer.MAX_VALUE);
            var number = lastStep + 1 + skipped;
            var events = evidence.eventsPage(query.caseId(), gameId, skipped, limit - entries.size());
            for (var event : events.events()) {
                entries.add(new Entry.Event(number++, event));
            }
            more = events.more();
        }
        var next = more ? entries.getLast().step() : null;
        return new ReplayPage(query.caseId(), manifestDigest, query.perspective(), entries, next);
    }
}
