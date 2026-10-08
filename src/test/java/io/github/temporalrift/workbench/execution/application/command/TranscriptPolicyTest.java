package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.evidence.DecisionTranscript;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;

class TranscriptPolicyTest {

    private static final Candidate KEEP = new Candidate.KeepHand(List.of(new UUID(0, 5)));
    private static final String WINDOW = "era1/hand-selection";

    private final TranscriptPolicy policy =
            new TranscriptPolicy(DecisionTranscript.parse(DecisionTranscript.render(List.of(new Slot(
                    new SlotId(new UUID(0, 1), 0, WINDOW),
                    new UUID(0, 2),
                    CandidateCodec.encode(KEEP),
                    SlotStatus.ACCEPTED,
                    "ACCEPTED")))));

    @Test
    void itChoosesExactlyWhatTheTranscriptAcceptedForTheSeatAndWindow() {
        assertThat(policy.decide(observation(0, handSelection()), Set.of(), entropy()))
                .isEqualTo(new PolicyDecision.Chosen(KEEP));
        assertThat(policy.miss()).isEmpty();
    }

    @Test
    void terminalReadinessIsConfirmedBecauseTheTranscriptHoldsNoSlotForIt() {
        assertThat(policy.decide(observation(0, new DecisionWindow.TerminalReadiness(1)), Set.of(), entropy()))
                .isEqualTo(new PolicyDecision.Chosen(new Candidate.ConfirmReady()));
    }

    @Test
    void aWindowTheTranscriptDoesNotCoverIsNotInventedAndIsReported() {
        var decision = policy.decide(observation(1, handSelection()), Set.of(), entropy());

        assertThat(decision).isInstanceOf(PolicyDecision.Exhausted.class);
        assertThat(policy.miss()).hasValueSatisfying(miss -> {
            assertThat(miss.seatIndex()).isEqualTo(1);
            assertThat(miss.windowKey()).isEqualTo(WINDOW);
            assertThat(miss.refused()).isNull();
        });
    }

    @Test
    void aDecisionTheServiceRefusedIsNotReplacedByAnotherChoice() {
        var decision = policy.decide(observation(0, handSelection()), Set.of(KEEP), entropy());

        assertThat(decision).isInstanceOf(PolicyDecision.Exhausted.class);
        assertThat(policy.miss())
                .hasValueSatisfying(miss -> assertThat(miss.refused()).isEqualTo(KEEP));
    }

    private static DecisionWindow handSelection() {
        return new DecisionWindow.HandSelection(1, List.of(), 1);
    }

    private static EntitledObservation observation(int seat, DecisionWindow window) {
        return new EntitledObservation(seat, Faction.ERASERS, List.of(), List.of(), window);
    }

    private static PolicyEntropy entropy() {
        return PolicyEntropy.derive(42, 0, WINDOW, 0);
    }
}
