package io.github.temporalrift.workbench.execution.domain.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.Faction;

class WinnerTest {

    @Test
    void anUnlistedScoreOrObjectiveWinnerListsItsWinType() {
        assertThat(new Winner(0, Faction.PROPHETS, WinType.FACTION_OBJECTIVE).winConditions())
                .containsExactly(WinType.FACTION_OBJECTIVE);
        assertThat(new Winner(0, Faction.PROPHETS, WinType.SCORE_THRESHOLD, null).winConditions())
                .containsExactly(WinType.SCORE_THRESHOLD);
    }

    @Test
    void anUnlistedOtherWinnerListsNothing() {
        assertThat(new Winner(0, Faction.PROPHETS, WinType.LAST_PLAYER_STANDING).winConditions())
                .isEmpty();
        assertThat(new Winner(0, Faction.PROPHETS, null).winConditions()).isEmpty();
    }

    @Test
    void aListedWinnerKeepsItsList() {
        var winner = new Winner(
                0,
                Faction.PROPHETS,
                WinType.SCORE_THRESHOLD,
                List.of(WinType.SCORE_THRESHOLD, WinType.FACTION_OBJECTIVE));

        assertThat(winner.winConditions()).containsExactly(WinType.SCORE_THRESHOLD, WinType.FACTION_OBJECTIVE);
    }
}
