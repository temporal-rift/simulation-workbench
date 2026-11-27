package io.github.temporalrift.workbench.search.domain.port.out;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.Faction;

/** Driven port to the analysis module: how often the hunted faction won, the objective of a policy hunt. */
public interface HuntObjective {

    /** An objective that is not available: a campaign that is not a hunt never asks for one. */
    HuntObjective UNAVAILABLE = (runId, tableId, faction, seeds) -> {
        throw new IllegalStateException("No hunt objective is configured");
    };

    /**
     * The succeeded games of one table of a run that hold {@code faction}, and the ones its seat won.
     *
     * @param seeds when not empty, only the games of these seeds
     * @throws IllegalArgumentException when the run is unknown
     */
    Tally tally(UUID runId, String tableId, Faction faction, List<String> seeds);

    /**
     * @param lower the lower bound of the 95% interval of the win ratio
     * @param upper the upper bound of the 95% interval of the win ratio
     */
    record Tally(int playerCount, int games, int wins, double lower, double upper) {

        /** The win rate over the fair share {@code 1 / playerCount}; 0 when no game was played. */
        public double ratio() {
            return games == 0 ? 0 : (double) wins / games * playerCount;
        }

        public Tally plus(Tally other) {
            return new Tally(
                    Math.max(playerCount, other.playerCount),
                    games + other.games,
                    wins + other.wins,
                    Double.NaN,
                    Double.NaN);
        }
    }
}
