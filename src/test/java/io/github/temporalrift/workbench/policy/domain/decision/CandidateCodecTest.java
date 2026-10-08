package io.github.temporalrift.workbench.policy.domain.decision;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

class CandidateCodecTest {

    private static final UUID A = new UUID(0, 1);
    private static final UUID B = new UUID(0, 2);
    private static final UUID C = new UUID(0, 3);

    static Stream<Arguments> candidates() {
        return Stream.of(
                Arguments.of(new Candidate.KeepHand(List.of(A, B)), "keep:" + A + "," + B),
                Arguments.of(new Candidate.Declare(DeclarationMode.RALLY, A, B), "declare:RALLY:" + A + ":" + B),
                Arguments.of(new Candidate.Decline(), "decline"),
                Arguments.of(
                        new Candidate.PlayCard(A, new Target.EventOutcome(B, C)),
                        "card:" + A + ":outcome:" + B + ":" + C),
                Arguments.of(
                        new Candidate.PlayCard(A, new Target.Disguise(CardCategory.INFORMATION)),
                        "card:" + A + ":disguise:INFORMATION"),
                Arguments.of(
                        new Candidate.PlayCard(A, new Target.OutcomePair(A, B, C)),
                        "card:" + A + ":pair:" + A + ":" + B + ":" + C),
                Arguments.of(
                        new Candidate.PlayCard(A, new Target.Events(List.of(B, C))),
                        "card:" + A + ":events:" + B + "," + C),
                Arguments.of(new Candidate.PlayCard(A, new Target.Player(B)), "card:" + A + ":player:" + B),
                Arguments.of(
                        new Candidate.PlayCard(A, new Target.Players(List.of(B, C))),
                        "card:" + A + ":players:" + B + "," + C),
                Arguments.of(new Candidate.PlayCard(A, null), "card:" + A + ":-"),
                Arguments.of(new Candidate.PlayCard(A, new Target.Events(List.of())), "card:" + A + ":events:"),
                Arguments.of(new Candidate.PlayCard(A, new Target.Players(List.of())), "card:" + A + ":players:"),
                Arguments.of(new Candidate.KeepHand(List.of()), "keep:"),
                Arguments.of(
                        new Candidate.PlaySpecial(SpecialAction.SEAL, new Target.Events(List.of(A))),
                        "special:SEAL:events:" + A),
                Arguments.of(new Candidate.Pass(), "pass"),
                Arguments.of(
                        new Candidate.PlayParadoxCard(A, new Target.EventOutcome(B, C)),
                        "paradox-card:" + A + ":outcome:" + B + ":" + C),
                Arguments.of(new Candidate.PassParadox(), "paradox-pass"),
                Arguments.of(new Candidate.ConfirmReady(), "ready"));
    }

    @ParameterizedTest
    @MethodSource("candidates")
    void everyCandidateHasOneCanonicalTextThatReadsBackAsItself(Candidate candidate, String text) {
        assertThat(CandidateCodec.encode(candidate)).isEqualTo(text);
        assertThat(CandidateCodec.decode(text)).isEqualTo(candidate);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "keep", "teleport:1", "card:not-a-uuid:-", "declare:RALLY:x", "special:NOPE:-"})
    void textThatIsNotARetainedCandidateIsRefused(String text) {
        assertThatThrownBy(() -> CandidateCodec.decode(text))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a retained candidate");
    }
}
