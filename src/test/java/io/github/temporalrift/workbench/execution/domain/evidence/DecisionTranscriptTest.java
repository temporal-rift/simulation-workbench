package io.github.temporalrift.workbench.execution.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotResolution;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

class DecisionTranscriptTest {

    private static final UUID CASE = new UUID(0, 1);

    @Test
    void aRenderedTranscriptReadsBackWithEveryDecisionBySeatAndWindow() {
        var keep = new Candidate.KeepHand(List.of(new UUID(0, 7)));
        var rendered = DecisionTranscript.render(List.of(
                slot(0, hand(1), CandidateCodec.encode(keep)),
                slot(1, hand(1), CandidateCodec.encode(new Candidate.Pass())),
                slot(1, WindowRef.actionRound(2, 3), CandidateCodec.encode(new Candidate.Pass()))));

        var transcript = DecisionTranscript.parse(rendered);

        assertThat(transcript.size()).isEqualTo(3);
        assertThat(transcript.decisionFor(0, hand(1))).contains(keep);
        assertThat(transcript.decisionFor(1, hand(1))).contains(new Candidate.Pass());
        assertThat(transcript.decisionFor(1, WindowRef.actionRound(2, 3))).contains(new Candidate.Pass());
        assertThat(transcript.decisionFor(1, hand(2))).isEmpty();
    }

    @Test
    void anEmptyTranscriptHasNoDecisions() {
        assertThat(DecisionTranscript.parse(DecisionTranscript.render(List.of()))
                        .size())
                .isZero();
    }

    @Test
    void aLineThatIsNotADecisionIsRefused() {
        assertThatThrownBy(() -> DecisionTranscript.parse("0\tonly-two-fields"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecisionTranscript.parse("0\tHAND_SELECTION\t1\t\tgarbage"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static WindowRef hand(int era) {
        return WindowRef.of(WindowKind.HAND_SELECTION, era);
    }

    private static Slot slot(int seat, WindowRef window, String request) {
        return new Slot(
                new SlotId(CASE, seat, window),
                new UUID(0, 2),
                request,
                SlotStatus.ACCEPTED,
                new SlotResolution.Accepted());
    }
}
