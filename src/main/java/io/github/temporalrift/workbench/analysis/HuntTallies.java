package io.github.temporalrift.workbench.analysis;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Lets another module count how often one faction won the games of a table of a run: the objective of a policy hunt.
 */
public interface HuntTallies {

    /**
     * Counts the succeeded games of one table of a run that hold {@code faction}, and the ones that faction's seat won.
     * A game that did not succeed is not counted.
     *
     * @param seeds when not empty, only the games of these seeds are counted
     * @throws IllegalArgumentException when the run is unknown
     */
    Tally tally(UUID runId, String tableId, Faction faction, List<String> seeds);

    /** The games of a faction at a table and the ones it won. */
    record Tally(int playerCount, int games, int wins) {

        /** The win rate over the fair share {@code 1 / playerCount}; 0 when no game was played. */
        public double ratio() {
            return games == 0 ? 0 : (double) wins / games * playerCount;
        }

        /** The two-sided 95% Clopper-Pearson interval of the win ratio: its lower and upper bound. */
        public double[] interval() {
            if (games == 0) {
                return new double[] {0, 0};
            }
            var share = io.github.temporalrift.workbench.analysis.domain.statistics.ClopperPearson.interval(
                    wins, games, 0.95);
            return new double[] {share[0] * playerCount, share[1] * playerCount};
        }
    }
}
