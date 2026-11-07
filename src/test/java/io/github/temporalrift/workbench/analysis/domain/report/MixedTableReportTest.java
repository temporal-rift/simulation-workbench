package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.Faction;

/** The cohorts of a table that seats two policies: only the cohort of one seat has a single policy. */
class MixedTableReportTest {

    private static final List<Faction> SEATS = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);
    private static final List<SeatPolicy> MIXED = List.of(
            new SeatPolicy("random", "1.0.0"),
            new SeatPolicy("faction-greedy", "1.0.0"),
            new SeatPolicy("faction-greedy", "1.0.0"));

    @Test
    void aPooledOrFactionSetCohortOfAMixedTableHasNoPolicyAndASeatCohortHasItsSeatsPolicy() {
        var played = new AnalyzedCase(
                new UUID(1, 1), "base", "random-seat-0", "1", 3, MIXED, SEATS, CaseState.PENDING, null);

        var keys = ReportCalculator.cohortKeys(List.of(played));

        assertThat(keys)
                .hasSize(1 + 3 + 1 + 3)
                .allSatisfy(key -> assertThat(key.tableId()).isEqualTo("random-seat-0"));
        var pooled = keys.stream()
                .filter(key -> key.factionSet() == null && key.seatIndex() == null)
                .findFirst();
        assertThat(pooled.orElseThrow().policyId()).isNull();
        assertThat(pooled.orElseThrow().policyVersion()).isNull();
        var factionSet = keys.stream()
                .filter(key -> key.factionSet() != null && key.seatIndex() == null)
                .findFirst();
        assertThat(factionSet.orElseThrow().policyId()).isNull();
        assertThat(keys.stream().filter(key -> key.factionSet() == null && key.seatIndex() != null))
                .extracting(CohortKey::seatIndex, CohortKey::policyId)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "random"),
                        org.assertj.core.groups.Tuple.tuple(1, "faction-greedy"),
                        org.assertj.core.groups.Tuple.tuple(2, "faction-greedy"));
    }

    @Test
    void aCohortOfAUniformTableHasTheTablesPolicyEverywhere() {
        var uniform = List.of(
                new SeatPolicy("random", "1.0.0"),
                new SeatPolicy("random", "1.0.0"),
                new SeatPolicy("random", "1.0.0"));
        var played =
                new AnalyzedCase(new UUID(1, 2), "base", "random", "1", 3, uniform, SEATS, CaseState.PENDING, null);

        assertThat(ReportCalculator.cohortKeys(List.of(played)))
                .allSatisfy(key -> assertThat(key.policyId()).isEqualTo("random"));
    }
}
