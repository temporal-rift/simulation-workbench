package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.shared.game.Faction;

/** A table that seats several fixed parameter sets of one bundle reports each seat's set by its artifact digest. */
class CohortKeyDigestTest {

    private static final String CAUTIOUS = "a".repeat(64);
    private static final String AGGRESSIVE = "b".repeat(64);
    private static final List<Faction> FACTIONS = List.of(Faction.values());

    private static List<SeatPolicy> table() {
        return List.of(
                new SeatPolicy("faction-search", "1.0.0", CAUTIOUS),
                new SeatPolicy("faction-search", "1.0.0", AGGRESSIVE),
                new SeatPolicy("faction-search", "1.0.0", CAUTIOUS),
                new SeatPolicy("faction-spread", "1.0.0"),
                new SeatPolicy("faction-spread", "1.0.0"));
    }

    private static CohortKey seat(int seatIndex) {
        return new CohortKey("v", "t", table(), 5, FACTIONS, seatIndex);
    }

    @Test
    void seatCohortsOfTheSameBundleAreToldApartByDigest() {
        assertThat(seat(0).policyId()).isEqualTo(seat(1).policyId());
        assertThat(seat(0).policyDigest()).isEqualTo(CAUTIOUS);
        assertThat(seat(1).policyDigest()).isEqualTo(AGGRESSIVE);
        assertThat(seat(0).text()).isNotEqualTo(seat(1).text());
    }

    @Test
    void aSeatOfTheSameSetKeepsTheSameIdentity() {
        assertThat(seat(0).policyDigest()).isEqualTo(seat(2).policyDigest());
    }

    @Test
    void aStandardSeatHasNoDigest() {
        assertThat(seat(3).policyId()).isEqualTo("faction-spread");
        assertThat(seat(3).policyDigest()).isNull();
    }

    @Test
    void thePooledCohortOfAMixedTableNamesNoPolicy() {
        var pooled = new CohortKey("v", "t", table(), 5, null, null);

        assertThat(pooled.policyId()).isNull();
        assertThat(pooled.policyDigest()).isNull();
    }
}
