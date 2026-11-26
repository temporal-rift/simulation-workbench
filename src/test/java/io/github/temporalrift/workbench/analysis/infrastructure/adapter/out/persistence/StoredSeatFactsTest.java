package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.analysis.domain.fact.SeatFacts;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.persistence.CaseFactsRepositoryAdapter.SeatDocument;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.StoredJson;

class StoredSeatFactsTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final StoredJson json = new StoredJson(mapper);

    @Test
    void theBetCountsSurviveStorage() {
        var seat = new SeatFacts(
                0,
                Faction.REVISIONISTS,
                false,
                false,
                10,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                0,
                Map.of(),
                Map.of(),
                null,
                0,
                0,
                0,
                5,
                3,
                2,
                Set.of(WinPath.SCORE_THRESHOLD, WinPath.FACTION_OBJECTIVE),
                Map.of("SECRET_OUTCOME_WON", 2, "PARADOX_CASCADE_PENALTY", 1),
                1);

        var read =
                json.read(json.write(SeatDocument.of(seat)), SeatDocument.class).facts();

        assertThat(read).isEqualTo(seat);
    }

    @Test
    void factsStoredBeforeTheBetCountsReadThemAsZero() {
        var stored = (ObjectNode) mapper.valueToTree(SeatDocument.of(empty()));
        stored.remove("betsPlaced");
        stored.remove("betsScored");
        stored.remove("betsWon");

        var read = json.read(stored.toString(), SeatDocument.class).facts();

        assertThat(read.betsPlaced()).isZero();
        assertThat(read.betsScored()).isZero();
        assertThat(read.betsWon()).isZero();
    }

    @Test
    void factsStoredBeforeTheWinConditionsReadThemAsNone() {
        var stored = (ObjectNode) mapper.valueToTree(SeatDocument.of(empty()));
        stored.remove("winConditions");

        var read = json.read(stored.toString(), SeatDocument.class).facts();

        assertThat(read.winConditions()).isEmpty();
    }

    @Test
    void factsStoredBeforeTheReasonOccurrencesReadThemAsNoneAndNoVoidedBets() {
        var stored = (ObjectNode) mapper.valueToTree(SeatDocument.of(empty()));
        stored.remove("reasonOccurrences");
        stored.remove("betsVoided");

        var read = json.read(stored.toString(), SeatDocument.class).facts();

        assertThat(read.reasonOccurrences()).isEmpty();
        assertThat(read.betsVoided()).isZero();
    }

    private static SeatFacts empty() {
        return new SeatFacts(
                0,
                Faction.WEAVERS,
                false,
                false,
                0,
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                0,
                Map.of());
    }
}
