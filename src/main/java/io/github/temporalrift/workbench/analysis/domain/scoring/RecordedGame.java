package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A recorded game as the scoring layer sees it: for each seat and each recorded era, the bet outcome and every score
 * reason it recorded, one occurrence each.
 *
 * @param seats the eras of each seat, indexed by seat then by era number minus one; every seat has {@code eras} items
 */
public record RecordedGame(
        int playerCount, String seed, List<Faction> factions, EndReason endReason, int eras, List<List<Era>> seats) {

    public RecordedGame {
        factions = List.copyOf(factions);
        seats = seats.stream().map(List::copyOf).toList();
    }

    /**
     * One seat-era.
     *
     * @param reasons the score reasons the seat recorded in the era, a joined record split and a repeated reason
     *     listed as often as it occurred
     */
    public record Era(BetOutcome outcome, List<String> reasons) {

        public Era {
            reasons = List.copyOf(reasons);
        }
    }
}
