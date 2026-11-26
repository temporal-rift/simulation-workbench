package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A recorded game as the scoring layer sees it: for each seat and each recorded era, what happened to its faction's
 * bet and every other score it recorded.
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
     * @param otherUnits the seat's recorded score of the era that does not settle its bet, in millionths of a point
     */
    public record Era(BetOutcome outcome, long otherUnits) {}
}
