package io.github.temporalrift.workbench.execution.support;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LedgerParticipantGateway;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.StepRecorder;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.CardGrade;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/**
 * A tiny real-service stand-in: every seat owes one hand selection, then the game ends. Decisions go
 * through the real ledger-backed gateway, so the decision transcript, in-doubt slots and reconciliation
 * behave exactly as against a real lane.
 */
public class FakeGame implements GameSession {

    /** Game state that outlives a single attempt. */
    static final class State {
        final Map<Integer, Candidate> accepted = new ConcurrentHashMap<>();
        final AtomicInteger submissions = new AtomicInteger();
    }

    private final CaseLane.CaseContext context;
    private final UUID gameId;
    private final State state;
    private final ScriptedLanes.Script script;
    private final LedgerParticipantGateway participants;

    FakeGame(
            CaseLane.CaseContext context,
            UUID gameId,
            State state,
            ScriptedLanes.Script script,
            CommandLedger ledger,
            EvidenceLedger evidence,
            Clock clock) {
        this.context = context;
        this.gameId = gameId;
        this.state = state;
        this.script = script;
        this.participants = new LedgerParticipantGateway(
                new Seats(),
                (seatIndex, windowKey) -> Optional.of(state.accepted.containsKey(seatIndex)),
                ledger,
                new StepRecorder(
                        evidence, context.caseId(), gameId, context.attemptId(), context.seed(), context::logicalEpoch),
                context.caseId(),
                context.attemptId(),
                clock);
    }

    /** Resolves what an interrupted attempt left in doubt, as attaching to a surviving game does. */
    void recoverInDoubt() {
        participants.recoverInDoubt();
    }

    /** The seats that have not decided yet. */
    public Set<Integer> undecided() {
        var seats = new java.util.HashSet<Integer>();
        context.seats().forEach(seat -> seats.add(seat.seatIndex()));
        seats.removeAll(state.accepted.keySet());
        return seats;
    }

    @Override
    public UUID gameId() {
        return gameId;
    }

    @Override
    public ParticipantGateway participants() {
        return participants;
    }

    @Override
    public GameProgress poll() {
        script.onPoll(context, this);
        var undecided = undecided();
        if (undecided.isEmpty()) {
            return new GameProgress.Ended();
        }
        return new GameProgress.Open(undecided.stream().sorted().toList());
    }

    @Override
    public void advanceClock() {
        // The scripted game never waits on logical time.
    }

    @Override
    public Optional<AuthoritativeEnding> ending() {
        if (!undecided().isEmpty() || !script.endingPublished(context)) {
            return Optional.empty();
        }
        return Optional.of(script.ending(context));
    }

    private final class Seats implements ParticipantGateway {

        @Override
        public EntitledObservation observe(int seatIndex) {
            var faction = Faction.valueOf(context.seats().get(seatIndex).faction());
            var window = state.accepted.containsKey(seatIndex)
                    ? new DecisionWindow.TerminalReadiness(1)
                    : new DecisionWindow.HandSelection(1, deal(seatIndex), 5);
            return script.observation(
                    context, new EntitledObservation(seatIndex, faction, events(), others(seatIndex), window));
        }

        @Override
        public SubmissionOutcome submit(int seatIndex, Candidate candidate) {
            if (candidate instanceof Candidate.ConfirmReady) {
                return new SubmissionOutcome.Accepted();
            }
            var refusal = script.rejection(context, seatIndex);
            if (refusal != null) {
                return new SubmissionOutcome.Rejected(refusal);
            }
            state.submissions.incrementAndGet();
            state.accepted.putIfAbsent(seatIndex, candidate);
            return script.loseAcknowledgement(context, seatIndex)
                    ? new SubmissionOutcome.Unacknowledged()
                    : new SubmissionOutcome.Accepted();
        }

        @Override
        public Reconciliation reconcile(int seatIndex) {
            var candidate = state.accepted.get(seatIndex);
            if (candidate instanceof Candidate.ConfirmReady || candidate == null) {
                return new Reconciliation.NotAccepted();
            }
            return new Reconciliation.Accepted(candidate);
        }

        private List<DealtCard> deal(int seatIndex) {
            var cards = new ArrayList<DealtCard>();
            for (var slot = 0; slot < 7; slot++) {
                cards.add(new DealtCard(
                        new UUID(seatIndex, slot + 1),
                        CardType.values()[slot],
                        CardGrade.II,
                        CardCategory.PROBABILITY_SHIFTER));
            }
            return cards;
        }

        private List<EventView> events() {
            return List.of(new EventView(
                    new UUID(9, 9),
                    List.of(new OutcomeView(new UUID(9, 1), 40, null), new OutcomeView(new UUID(9, 2), 60, null))));
        }

        private List<UUID> others(int seatIndex) {
            return context.seats().stream()
                    .filter(seat -> seat.seatIndex() != seatIndex)
                    .map(seat -> new UUID(7, seat.seatIndex()))
                    .toList();
        }
    }
}
