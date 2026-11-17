package io.github.temporalrift.workbench.analysis.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Criterion version 3 measures the same lines as version 2 on the faction-greedy-v2 table. */
class CriterionVersionThreeTest {

    private static final UUID RUN = new UUID(0, 13);
    private static final String GREEDY_TWO = "faction-greedy-v2";

    @Test
    void theFactionLinesReadTheVersionTwoGreedyTable() {
        var run = new SyntheticRun("base", GREEDY_TWO).table(GREEDY_TWO, Collections.nCopies(5, GREEDY_TWO));
        var set = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);
        for (var seed = 1; seed <= 6; seed++) {
            for (var shift = 0; shift < set.size(); shift++) {
                var seats = new ArrayList<>(set.subList(shift, set.size()));
                seats.addAll(set.subList(0, shift));
                run.win(String.valueOf(seed), seats, seed % 3);
            }
        }

        var evaluation = RunEvaluator.evaluate(
                RUN,
                "base",
                TestCriteria.v3(),
                EvaluationMode.SCREENING,
                AnalysisVersion.CURRENT,
                run.cases(),
                run.facts());

        var measured = evaluation.lines().stream()
                .filter(line -> line.lineId().equals("G-05")
                        && line.playerCount() == 3
                        && line.status() != MetricStatus.NO_DATA)
                .toList();
        assertThat(measured).hasSize(3).allSatisfy(line -> {
            assertThat(line.tableId()).isEqualTo(GREEDY_TWO);
            assertThat(line.policyId()).isEqualTo(GREEDY_TWO);
            assertThat(line.value()).isNotNull();
        });
        assertThat(measured).extracting(line -> line.dimensions().faction()).containsExactlyInAnyOrderElementsOf(set);
    }
}
