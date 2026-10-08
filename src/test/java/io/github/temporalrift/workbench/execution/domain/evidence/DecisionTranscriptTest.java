package io.github.temporalrift.workbench.execution.domain.evidence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;

class DecisionTranscriptTest {

    private static final UUID CASE = new UUID(0, 1);

    @Test
    void aRenderedTranscriptReadsBackWithEveryDecisionBySeatAndWindow() {
        var keep = new Candidate.KeepHand(List.of(new UUID(0, 7)));
        var rendered = DecisionTranscript.render(List.of(
                slot(0, "era1/hand-selection", CandidateCodec.encode(keep)),
                slot(1, "era1/hand-selection", CandidateCodec.encode(new Candidate.Pass()))));

        var transcript = DecisionTranscript.parse(rendered);

        assertThat(transcript.size()).isEqualTo(2);
        assertThat(transcript.decisionFor(0, "era1/hand-selection")).contains(keep);
        assertThat(transcript.decisionFor(1, "era1/hand-selection")).contains(new Candidate.Pass());
        assertThat(transcript.decisionFor(1, "era2/hand-selection")).isEmpty();
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
        assertThatThrownBy(() -> DecisionTranscript.parse("0\tera1/hand-selection\tgarbage"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Slot slot(int seat, String window, String request) {
        return new Slot(new SlotId(CASE, seat, window), new UUID(0, 2), request, SlotStatus.ACCEPTED, "ACCEPTED");
    }
}
