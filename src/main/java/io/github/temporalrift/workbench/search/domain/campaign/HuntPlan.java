package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;

/**
 * The fixed plan of a policy hunt: 64 candidates on 32 games, the best 16 on 128 and the best 4 on 300, then a
 * short list of those 4, and a confirmation of the best on 300 fresh games.
 *
 * <p>Every game of a hunt has the hunted faction in the set, and a seed plays every seat rotation of every faction
 * set that holds it, so a seed plays {@link #casesPerSeed} games. A round plays the smallest whole number of seeds
 * that gives at least the games it is planned for, which keeps the games of a round an exact multiple of that number
 * and the seeds of each round a prefix of the next one's.
 */
public final class HuntPlan {

    /** The table a confirmation plays the candidate on, and the one it plays the standard seat on. */
    public static final String CANDIDATE_TABLE = "candidate";

    public static final String STANDARD_TABLE = "standard";

    public static final int CANDIDATES = 64;
    public static final int ETA = 4;
    public static final int SHORT_LIST = 4;

    /** The round that plays the last four candidates and short-lists all of them. */
    public static final int LAST_ROUND = 2;

    /** The games each candidate has played at the end of rounds 0, 1 and 2. */
    public static final List<Integer> GAMES_BY_ROUND = List.of(32, 128, 300);

    /** The fresh games of a confirmation, for the candidate and for the standard seat. */
    public static final int CONFIRMATION_GAMES = 300;

    /** A candidate whose win ratio reaches this multiple of the normal ratio is flagged. */
    public static final double FLAG_FACTOR = 1.25;

    /** A candidate whose win ratio reaches this multiple of the normal ratio is an exploit. */
    public static final double EXPLOIT_FACTOR = 1.5;

    /** The normal ratio never falls below the ratio of a seat at its fair share. */
    public static final double FAIR_RATIO = 1.0;

    private static final int OTHER_FACTIONS = 4;

    private HuntPlan() {}

    /**
     * The games one seed plays: the faction sets of {@code playerCount} factions that hold the hunted faction, each
     * under every one of its {@code playerCount} seat rotations.
     */
    public static int casesPerSeed(int playerCount) {
        return binomial(OTHER_FACTIONS, playerCount - 1) * playerCount;
    }

    /** The seeds that play at least {@code games} games. */
    public static int seedsFor(int games, int playerCount) {
        return Math.ceilDiv(games, casesPerSeed(playerCount));
    }

    /** The seeds every candidate of {@code round} has played once the round is done. */
    public static int seedsInRound(int round, int playerCount) {
        return seedsFor(GAMES_BY_ROUND.get(Math.min(round, LAST_ROUND)), playerCount);
    }

    /** The seeds of a confirmation. */
    public static int confirmationSeeds(int playerCount) {
        return seedsFor(CONFIRMATION_GAMES, playerCount);
    }

    /** The search algorithm of a hunt at a table size: the plan's candidates, first round, {@code eta} and list. */
    public static SearchAlgorithm algorithm(int playerCount) {
        return new SearchAlgorithm(
                SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2,
                CANDIDATES,
                seedsInRound(0, playerCount),
                ETA,
                SHORT_LIST);
    }

    private static int binomial(int n, int k) {
        if (k < 0 || k > n) {
            return 0;
        }
        var result = 1;
        for (var i = 1; i <= k; i++) {
            result = result * (n - k + i) / i;
        }
        return result;
    }
}
