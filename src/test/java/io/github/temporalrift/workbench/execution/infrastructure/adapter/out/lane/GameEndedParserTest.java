package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

class GameEndedParserTest {

    private static final UUID GAME = new UUID(1, 1);
    private static final UUID PLAYER = new UUID(2, 2);
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void readsTheEndReasonAndEveryPlayersFinalScore() {
        var ended = GameEndedParser.parse(
                mapper,
                event("{\"gameId\":\"" + GAME + "\",\"endReason\":\"TIMELINE_STABILIZED\","
                        + "\"finalScores\":[{\"playerId\":\"" + PLAYER + "\",\"faction\":\"WEAVERS\",\"score\":4}]}"));

        assertThat(ended.endReason()).isEqualTo(EndReason.TIMELINE_STABILIZED);
        assertThat(ended.finalScores()).isEqualTo(Map.of(PLAYER, 4));
    }

    @Test
    void aPayloadThatIsNotAGameEndedFailsTheAttemptAsAContractMismatch() {
        var invalid = event("{\"unexpected\":true}");

        assertThatThrownBy(() -> GameEndedParser.parse(mapper, invalid))
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONTRACT_MISMATCH));
    }

    private static ObservedEvent event(String payload) {
        return new ObservedEvent(
                "game.events", 0, 0, UUID.randomUUID(), "GameEnded", null, null, GAME, null, 1, payload);
    }
}
