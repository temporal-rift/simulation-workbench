package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.CardGrade;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

class LedgerParticipantGatewayTest {

    private static final UUID CASE = new UUID(0, 1);
    private static final UUID ATTEMPT = new UUID(0, 2);
    private static final UUID GAME = new UUID(0, 3);
    private static final Instant LOGICAL = Instant.parse("2026-01-01T00:00:00Z");
    private static final String WINDOW = "era1/hand-selection";

    private final InMemoryCommandLedger ledger = new InMemoryCommandLedger();
    private final InMemoryEvidenceLedger evidence = new InMemoryEvidenceLedger();
    private final ScriptedDelegate delegate = new ScriptedDelegate();
    private final List<Optional<Boolean>> held = new ArrayList<>();
    private LedgerParticipantGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new LedgerParticipantGateway(
                delegate,
                (seat, window) -> held.isEmpty() ? Optional.empty() : held.getFirst(),
                ledger,
                recorder(),
                CASE,
                ATTEMPT,
                Clock.systemUTC());
        gateway.observe(0);
    }

    @Test
    void anAcceptedCommandIsRecordedOnceAndNeverSentAgain() {
        delegate.outcomes.add(new SubmissionOutcome.Accepted());

        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Accepted.class);
        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Accepted.class);

        assertThat(delegate.sent).hasSize(1);
        assertThat(ledger.accepted(CASE))
                .singleElement()
                .satisfies(slot -> assertThat(slot.id().windowKey()).isEqualTo(WINDOW));
    }

    @Test
    void aLostAcknowledgementLeavesTheSlotInDoubtAndBlocksAnyResend() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());

        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Unacknowledged.class);
        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Unacknowledged.class);

        assertThat(delegate.sent).hasSize(1);
        assertThat(ledger.inDoubt(CASE)).hasSize(1);
        assertThat(ledger.accepted(CASE)).isEmpty();
    }

    @Test
    void reconciliationThatFindsTheCommandAcceptsTheSlot() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());
        gateway.submit(0, keep());
        delegate.reconciliation = new Reconciliation.Accepted(keep());

        assertThat(gateway.reconcile(0)).isInstanceOf(Reconciliation.Accepted.class);

        assertThat(ledger.accepted(CASE)).hasSize(1);
        assertThat(ledger.inDoubt(CASE)).isEmpty();
    }

    @Test
    void aSlotConfirmedAbsentFromCurrentStateMayBeSentAgain() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());
        delegate.outcomes.add(new SubmissionOutcome.Accepted());
        gateway.submit(0, keep());
        delegate.reconciliation = new Reconciliation.NotAccepted();
        gateway.reconcile(0);

        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Accepted.class);

        assertThat(delegate.sent).hasSize(2);
        assertThat(ledger.accepted(CASE)).hasSize(1);
    }

    @Test
    void aStaleReadNeitherAcceptsNorReleasesTheSlot() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());
        gateway.submit(0, keep());
        delegate.reconciliation = new Reconciliation.Pending();

        gateway.reconcile(0);

        assertThat(ledger.inDoubt(CASE)).hasSize(1);
        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Unacknowledged.class);
        assertThat(delegate.sent).hasSize(1);
    }

    @Test
    void aDefiniteRejectionReleasesTheSlotForADifferentCandidate() {
        delegate.outcomes.add(new SubmissionOutcome.Rejected("422-11"));
        delegate.outcomes.add(new SubmissionOutcome.Accepted());

        assertThat(gateway.submit(0, keep())).isEqualTo(new SubmissionOutcome.Rejected("422-11"));
        assertThat(gateway.submit(0, new Candidate.KeepHand(List.of(new UUID(0, 9)))))
                .isNotNull();

        assertThat(delegate.sent).hasSize(2);
        assertThat(ledger.accepted(CASE)).hasSize(1);
    }

    @Test
    void anAcceptedSlotIsNeverDowngradedByALaterAbsentReading() {
        delegate.outcomes.add(new SubmissionOutcome.Accepted());
        gateway.submit(0, keep());
        delegate.reconciliation = new Reconciliation.NotAccepted();

        gateway.reconcile(0);

        assertThat(ledger.accepted(CASE)).hasSize(1);
    }

    @Test
    void aSlotAcceptedByAnEarlierAttemptIsNotSentByTheRecoveringOne() {
        ledger.put(new SlotId(CASE, 0, WINDOW), new UUID(0, 99), keep().toString(), SlotStatus.ACCEPTED);

        assertThat(gateway.submit(0, keep())).isInstanceOf(SubmissionOutcome.Accepted.class);

        assertThat(delegate.sent).isEmpty();
    }

    @Test
    void recoveryResolvesInDoubtSlotsFromTheServiceAcceptedState() {
        ledger.put(new SlotId(CASE, 0, WINDOW), ATTEMPT, "kept", SlotStatus.SENT);
        ledger.put(new SlotId(CASE, 1, WINDOW), ATTEMPT, "absent", SlotStatus.SENT);
        var recovering = new LedgerParticipantGateway(
                delegate,
                (seat, window) -> Optional.of(seat == 0),
                ledger,
                recorder(),
                CASE,
                ATTEMPT,
                Clock.systemUTC());

        recovering.recoverInDoubt();

        assertThat(ledger.find(new SlotId(CASE, 0, WINDOW)).orElseThrow().status())
                .isEqualTo(SlotStatus.ACCEPTED);
        assertThat(ledger.find(new SlotId(CASE, 1, WINDOW)).orElseThrow().status())
                .isEqualTo(SlotStatus.NOT_SPENT);
    }

    @Test
    void recoveryLeavesTheSlotInDoubtWhileAcceptedStateIsNotCurrent() {
        ledger.put(new SlotId(CASE, 0, WINDOW), ATTEMPT, "kept", SlotStatus.SENT);

        gateway.recoverInDoubt();

        assertThat(ledger.inDoubt(CASE)).hasSize(1);
    }

    @Test
    void terminalReadinessIsNotADecisionSlot() {
        delegate.outcomes.add(new SubmissionOutcome.Accepted());

        gateway.submit(0, new Candidate.ConfirmReady());

        assertThat(ledger.accepted(CASE)).isEmpty();
        assertThat(ledger.inDoubt(CASE)).isEmpty();
    }

    @Test
    void everyCommandIsRetainedWithTheObservationItWasDecidedFromAndItsOutcome() {
        delegate.outcomes.add(new SubmissionOutcome.Accepted());

        gateway.submit(0, keep());

        assertThat(evidence.steps(CASE, GAME)).singleElement().satisfies(step -> {
            assertThat(step.step()).isZero();
            assertThat(step.seatIndex()).isZero();
            assertThat(step.windowKey()).isEqualTo(WINDOW);
            assertThat(step.phase()).isEqualTo("HAND_SELECTION");
            assertThat(step.era()).isEqualTo(1);
            assertThat(step.round()).isNull();
            assertThat(step.logicalTime()).isEqualTo(LOGICAL);
            assertThat(step.outcome()).isEqualTo(StepOutcome.ACCEPTED);
            assertThat(step.decision()).isEqualTo(CandidateCodec.encode(keep()));
            assertThat(step.observation()).contains("\"seatIndex\":0").contains("\"kind\":\"HandSelection\"");
            assertThat(step.entropy())
                    .isEqualTo("{\"drawIndex\":0,\"policySeed\":\"42\",\"seat\":0,"
                            + "\"stream\":\"policy-entropy/v1\",\"window\":\"era1/hand-selection\"}");
        });
    }

    @Test
    void rejectionsAreRetainedAndEachOneAdvancesTheEntropyDraw() {
        delegate.outcomes.add(new SubmissionOutcome.Rejected("422-11"));
        delegate.outcomes.add(new SubmissionOutcome.Accepted());

        gateway.submit(0, keep());
        gateway.submit(0, new Candidate.KeepHand(List.of(new UUID(0, 9))));

        assertThat(evidence.steps(CASE, GAME))
                .extracting(step -> step.outcome() + ":" + step.outcomeCode())
                .containsExactly("REJECTED:422-11", "ACCEPTED:null");
        assertThat(evidence.steps(CASE, GAME).get(1).entropy()).contains("\"drawIndex\":1");
    }

    @Test
    void anUnacknowledgedCommandIsResolvedInTheEvidenceOnceAcceptedStateIsKnown() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());
        gateway.submit(0, keep());
        assertThat(evidence.steps(CASE, GAME).getFirst().outcome()).isEqualTo(StepOutcome.UNACKNOWLEDGED);

        delegate.reconciliation = new Reconciliation.Accepted(keep());
        gateway.reconcile(0);

        assertThat(evidence.steps(CASE, GAME))
                .singleElement()
                .satisfies(step -> assertThat(step.outcome()).isEqualTo(StepOutcome.ACCEPTED));
    }

    @Test
    void aCommandConfirmedAbsentIsRetainedAsNotSpent() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());
        gateway.submit(0, keep());

        delegate.reconciliation = new Reconciliation.NotAccepted();
        gateway.reconcile(0);

        assertThat(evidence.steps(CASE, GAME))
                .singleElement()
                .satisfies(step -> assertThat(step.outcome()).isEqualTo(StepOutcome.NOT_SPENT));
    }

    @Test
    void recoveringInDoubtSlotsResolvesTheirRetainedSteps() {
        delegate.outcomes.add(new SubmissionOutcome.Unacknowledged());
        gateway.submit(0, keep());
        held.add(Optional.of(true));

        gateway.recoverInDoubt();

        assertThat(evidence.steps(CASE, GAME))
                .singleElement()
                .satisfies(step -> assertThat(step.outcome()).isEqualTo(StepOutcome.ACCEPTED));
    }

    @Test
    void terminalReadinessIsRetainedAlthoughItIsNotADecisionSlot() {
        delegate.outcomes.add(new SubmissionOutcome.Accepted());

        gateway.submit(0, new Candidate.ConfirmReady());

        assertThat(evidence.steps(CASE, GAME))
                .singleElement()
                .satisfies(step -> assertThat(step.decision()).isEqualTo("ready"));
    }

    @Test
    void aCommandAnotherAttemptAlreadyAcceptedIsNotRetainedAgain() {
        ledger.put(new SlotId(CASE, 0, WINDOW), new UUID(0, 99), CandidateCodec.encode(keep()), SlotStatus.ACCEPTED);

        gateway.submit(0, keep());

        assertThat(evidence.steps(CASE, GAME)).isEmpty();
    }

    private StepRecorder recorder() {
        return new StepRecorder(evidence, CASE, GAME, ATTEMPT, "42", () -> LOGICAL);
    }

    private static Candidate keep() {
        return new Candidate.KeepHand(List.of(new UUID(0, 5)));
    }

    private static final class ScriptedDelegate implements ParticipantGateway {

        final Queue<SubmissionOutcome> outcomes = new ArrayDeque<>();
        final List<Candidate> sent = new ArrayList<>();
        Reconciliation reconciliation = new Reconciliation.Pending();

        @Override
        public EntitledObservation observe(int seatIndex) {
            return new EntitledObservation(
                    seatIndex,
                    Faction.ERASERS,
                    List.of(),
                    List.of(),
                    new DecisionWindow.HandSelection(
                            1,
                            List.of(new DealtCard(
                                    new UUID(0, 5), CardType.PUSH, CardGrade.I, CardCategory.PROBABILITY_SHIFTER)),
                            1));
        }

        @Override
        public SubmissionOutcome submit(int seatIndex, Candidate candidate) {
            sent.add(candidate);
            return outcomes.remove();
        }

        @Override
        public Reconciliation reconcile(int seatIndex) {
            return reconciliation;
        }
    }
}
