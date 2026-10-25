package io.github.temporalrift.workbench.execution.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase.Entry;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase.Perspective;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase.Query;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase.ReplayPage;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

class GetCaseReplayQueryHandlerTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final UUID CASE = new UUID(0, 2);
    private static final UUID GAME = new UUID(0, 3);
    private static final UUID EXPERIMENT = new UUID(0, 4);
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String DIGEST = "a".repeat(64);
    private static final int STEPS = 200;
    private static final int EVENTS = 500;

    private final RunRepository runs = mock(RunRepository.class);
    private final ExperimentSource experiments = mock(ExperimentSource.class);
    private final RecordingLedger ledger = new RecordingLedger();
    private final GetCaseReplayQueryHandler handler = new GetCaseReplayQueryHandler(runs, experiments, ledger);

    @BeforeEach
    void fixture() {
        var run = mock(Run.class);
        when(run.experimentId()).thenReturn(EXPERIMENT);
        when(runs.find(RUN)).thenReturn(Optional.of(run));
        when(runs.findCase(RUN, CASE)).thenReturn(Optional.of(logicalCase()));
        when(runs.attemptsOf(CASE))
                .thenReturn(List.of(
                        new Attempt(UUID.randomUUID(), CASE, 0, AttemptState.SUCCEEDED, GAME, "lane", NOW, NOW, null)));
        ledger.pin(CASE, DIGEST, "{}", NOW);
        for (var i = 0; i < STEPS; i++) {
            ledger.append(CASE, GAME, UUID.randomUUID(), step(i % 3));
        }
        for (var i = 0; i < EVENTS; i++) {
            ledger.retain(CASE, UUID.randomUUID(), event(i % 2 == 0 ? "game.events" : "timeline.events", i / 2));
        }
        ledger.reset();
    }

    @Test
    void playerPagesLoadAtMostOnePageOfStepsAndNoEvents() {
        var seatSteps = IntStream.range(0, STEPS).filter(i -> i % 3 == 1).toArray();
        var first = handler.handle(player(1, null, 50));
        var middle = handler.handle(player(1, 120, 50));
        var last = handler.handle(player(1, seatSteps[seatSteps.length - 20], 50));

        assertThat(first.entries()).hasSize(50);
        assertThat(first.nextStep()).isEqualTo(first.entries().getLast().step());
        var middleSteps = middle.entries().stream().map(Entry::step).toList();
        assertThat(middleSteps).isNotEmpty().allMatch(step -> step > 120 && step % 3 == 1);
        assertThat(last.entries()).hasSize(19);
        assertThat(last.nextStep()).isNull();
        assertThat(ledger.stepRowsRequested).containsExactly(51, 51, 51);
        assertThat(ledger.fullStepReads).isZero();
        assertThat(ledger.fullEventReads).isZero();
        assertThat(ledger.eventPagesRequested).isZero();
        assertThat(ledger.payloadsVerified).isZero();
    }

    @Test
    void anObserverPageInsideTheStepsLoadsNoEvents() {
        var page = handler.handle(observer(null, 50));

        assertThat(page.entries()).extracting(Entry::step).containsExactlyElementsOf(range(0, 50));
        assertThat(page.nextStep()).isEqualTo(49);
        assertThat(ledger.eventPagesRequested).isZero();
        assertThat(ledger.payloadsVerified).isZero();
    }

    @Test
    void anObserverPageCrossingFromStepsIntoEventsContinuesTheNumbering() {
        var page = handler.handle(observer(STEPS - 11, 50));

        assertThat(page.entries()).extracting(Entry::step).containsExactlyElementsOf(range(STEPS - 10, STEPS + 40));
        assertThat(page.entries().subList(0, 10)).allMatch(Entry.Command.class::isInstance);
        assertThat(page.entries().subList(10, 50)).allMatch(Entry.Event.class::isInstance);
        assertThat(page.nextStep()).isEqualTo(STEPS + 39);
        assertThat(ledger.payloadsVerified).isEqualTo(40);
    }

    @Test
    void anObserverPageInsideTheEventsSkipsTheEarlierEventsAndVerifiesOnlyItsOwn() {
        var page = handler.handle(observer(STEPS + 99, 50));

        assertThat(page.entries()).extracting(Entry::step).containsExactlyElementsOf(range(STEPS + 100, STEPS + 150));
        assertThat(page.entries()).allMatch(Entry.Event.class::isInstance);
        assertThat(ledger.payloadsVerified).isEqualTo(50);
        assertThat(ledger.fullEventReads).isZero();
    }

    @Test
    void observerPagesMatchTheWholeCaseListedAtOnce() {
        var expected = new ArrayList<Entry>();
        ledger.steps(CASE, GAME).forEach(step -> expected.add(new Entry.Command(step.step(), step)));
        var number = STEPS;
        for (var event : ledger.events(CASE, GAME)) {
            expected.add(new Entry.Event(number++, event));
        }

        var walked = new ArrayList<Entry>();
        Integer cursor = null;
        do {
            ReplayPage page = handler.handle(observer(cursor, 37));
            walked.addAll(page.entries());
            cursor = page.nextStep();
        } while (cursor != null);

        assertThat(walked).isEqualTo(expected);
    }

    @Test
    void theLastObserverPageHasNoNextStep() {
        var page = handler.handle(observer(STEPS + EVENTS - 6, 50));

        assertThat(page.entries()).hasSize(5);
        assertThat(page.nextStep()).isNull();
    }

    @Test
    void aPageEndingExactlyOnTheLastStepStillPointsAtTheFollowingEvents() {
        var page = handler.handle(observer(STEPS - 51, 50));

        assertThat(page.entries()).hasSize(50);
        assertThat(page.nextStep()).isEqualTo(STEPS - 1);
        assertThat(ledger.payloadsVerified).isZero();
    }

    @Test
    void aCursorBeyondTheLastEventYieldsAnEmptyLastPage() {
        var page = handler.handle(observer(Integer.MAX_VALUE, 50));

        assertThat(page.entries()).isEmpty();
        assertThat(page.nextStep()).isNull();
    }

    @Test
    void aPinnedCaseNeverReadsTheExperiment() {
        var page = handler.handle(player(0, null, 5));

        assertThat(page.manifestDigest()).isEqualTo(DIGEST);
        verifyNoInteractions(experiments);
    }

    @Test
    void anUnpinnedCaseFallsBackToTheExperimentPlanOrIsNotFound() {
        ledger.purge(CASE);
        var plan = mock(ExperimentSource.Plan.class);
        when(plan.manifestDigest()).thenReturn("b".repeat(64));
        when(experiments.plan(EXPERIMENT)).thenReturn(Optional.of(plan));

        assertThat(handler.handle(player(0, null, 5)).manifestDigest()).isEqualTo("b".repeat(64));

        when(experiments.plan(EXPERIMENT)).thenReturn(Optional.empty());
        var replay = player(0, null, 5);
        assertThatThrownBy(() -> handler.handle(replay)).isInstanceOf(RunNotFoundException.class);
    }

    private static List<Integer> range(int from, int to) {
        return IntStream.range(from, to).boxed().toList();
    }

    private static Query player(int seat, Integer after, int limit) {
        return new Query(RUN, CASE, Perspective.PLAYER, seat, after, limit);
    }

    private static Query observer(Integer after, int limit) {
        return new Query(RUN, CASE, Perspective.OBSERVER, null, after, limit);
    }

    private static LogicalCase logicalCase() {
        return new LogicalCase(
                CASE,
                RUN,
                UUID.randomUUID(),
                0,
                "base",
                new BundleDigests(DIGEST, DIGEST),
                new LaneFit("f".repeat(64)),
                "1",
                3,
                List.of(
                        new SeatPlan(0, Faction.ERASERS, "random-v1", "1"),
                        new SeatPlan(1, Faction.PROPHETS, "random-v1", "1"),
                        new SeatPlan(2, Faction.WEAVERS, "random-v1", "1")),
                CaseState.SUCCEEDED,
                null);
    }

    private static StepRecord step(int seat) {
        var observation = new EntitledObservation(
                seat, Faction.ERASERS, List.of(), List.of(), new DecisionWindow.HandSelection(1, List.of(), 1));
        return new StepRecord(
                0,
                seat,
                WindowRef.of(WindowKind.HAND_SELECTION, 1),
                NOW,
                observation,
                new Candidate.Pass(),
                StepOutcome.ACCEPTED,
                null,
                null);
    }

    private static ObservedEvent event(String source, int offset) {
        return new ObservedEvent(
                source,
                0,
                offset,
                UUID.randomUUID(),
                "E",
                new UUID(8, 8),
                "Game",
                GAME,
                NOW,
                1,
                "{\"n\":" + offset + "}");
    }

    /** Counts what the handler asks the ledger for. */
    private static final class RecordingLedger extends InMemoryEvidenceLedger {

        private final List<Integer> stepRowsRequested = new ArrayList<>();
        private int eventPagesRequested;
        private int fullStepReads;
        private int fullEventReads;
        private int payloadsVerified;

        void reset() {
            stepRowsRequested.clear();
            eventPagesRequested = 0;
            fullStepReads = 0;
            fullEventReads = 0;
            payloadsVerified = 0;
        }

        @Override
        public synchronized List<StepRecord> steps(UUID scopeId, UUID gameId) {
            fullStepReads++;
            return super.steps(scopeId, gameId);
        }

        @Override
        public synchronized List<ObservedEvent> events(UUID scopeId, UUID gameId) {
            fullEventReads++;
            var all = super.events(scopeId, gameId);
            payloadsVerified += all.size();
            return all;
        }

        @Override
        public synchronized List<StepRecord> stepsPage(
                UUID scopeId, UUID gameId, Integer seatIndex, int afterStep, int limit) {
            stepRowsRequested.add(limit);
            return super.stepsPage(scopeId, gameId, seatIndex, afterStep, limit);
        }

        @Override
        public synchronized EventPage eventsPage(UUID scopeId, UUID gameId, int offset, int limit) {
            eventPagesRequested++;
            var page = super.eventsPage(scopeId, gameId, offset, limit);
            payloadsVerified += page.events().size();
            return page;
        }
    }
}
