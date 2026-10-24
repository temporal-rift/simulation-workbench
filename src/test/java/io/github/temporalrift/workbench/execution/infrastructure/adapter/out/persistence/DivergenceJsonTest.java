package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.StoredJson;
import io.github.temporalrift.workbench.shared.window.WindowKind;
import io.github.temporalrift.workbench.shared.window.WindowRef;

class DivergenceJsonTest {

    private static final WindowRef ROUND = WindowRef.actionRound(1, 2);
    private static final Candidate PASS = new Candidate.Pass();
    private static final Divergence.StepSummary SUMMARY = new Divergence.StepSummary(1, ROUND, PASS);
    private static final Divergence.ObservedStep OBSERVED = new Divergence.ObservedStep(
            1,
            ROUND,
            new EntitledObservation(
                    1,
                    Faction.WEAVERS,
                    List.of(),
                    List.of(new UUID(0, 4)),
                    new DecisionWindow.ActionRound(1, 2, List.of(), List.of(), List.of())));

    private final DivergenceJson json =
            new DivergenceJson(new StoredJson(JsonMapper.builder().build()));

    @Test
    void everyKindOfDivergenceIsRebuiltAsTheSameVariant() {
        var winner = new Winner(0, Faction.ERASERS, WinType.SCORE_THRESHOLD);
        var score = new FinalScore(0, Faction.ERASERS, 30);

        var divergences = Stream.of(
                new Divergence.Window(3, SUMMARY, new Divergence.StepSummary(2, ROUND, PASS)),
                new Divergence.Observation(3, OBSERVED, OBSERVED),
                new Divergence.Decision(3, SUMMARY, new Divergence.StepSummary(1, ROUND, new Candidate.PassParadox())),
                new Divergence.MissingStep(4, SUMMARY),
                new Divergence.UnexpectedStep(4, SUMMARY),
                new Divergence.EndReason(5, EndReason.WIN_CONDITION_MET, EndReason.RESOLUTION_FAILED),
                new Divergence.Winners(5, List.of(winner), List.of()),
                new Divergence.FinalScores(5, List.of(score), List.of(new FinalScore(0, Faction.ERASERS, 31))),
                new Divergence.Eras(5, 2, 3),
                new Divergence.SemanticDigest(5, "a", "b"),
                new Divergence.UnexpectedWindow(2, 1, ROUND),
                new Divergence.UnexpectedWindow(2, 1, null),
                new Divergence.CommandRejected(
                        2, 1, WindowRef.of(WindowKind.HAND_SELECTION, 1), PASS, List.of("422-11", "422-12")));

        assertThat(divergences)
                .isNotEmpty()
                .allSatisfy(divergence ->
                        assertThat(json.read(json.write(divergence))).isEqualTo(divergence));
    }
}
