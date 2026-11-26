package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.FactionSets;
import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * Plays the scoring layer of a game alone: each seat draws one bet outcome per era from its rates, scores it under its
 * faction's engine, and a points race decides the winners. It never plays cards or resolves events.
 *
 * <p>Every game draws from a stream derived from the seed, the player count and the game index only, and the results
 * are summed as exact integers, so an evaluation is the same on any number of threads.
 */
public final class ScoringEvaluator {

    private static final String STREAM = "scoring-model";
    private static final int FACTIONS = Faction.values().length;
    /** The least common multiple of the possible number of sharing winners, 1 to 5. */
    private static final int SHARE_UNITS = 60;

    private static final double Z_95 = 1.96;
    private static final double UNIT = 0x1.0p-53;

    // Layout of the per-player-count tally.
    private static final int PRESENT = 0;
    private static final int SHARE_SUM = FACTIONS;
    private static final int SHARE_SQUARES = 2 * FACTIONS;
    private static final int ERAS = 3 * FACTIONS;
    private static final int THRESHOLD_ENDINGS = ERAS + 1;
    private static final int TALLY_SIZE = THRESHOLD_ENDINGS + 1;

    private ScoringEvaluator() {}

    public static ScoringEvaluation evaluate(ScoringModel model) {
        var cells = new ArrayList<ScoringEvaluation.Cell>();
        var endings = new ArrayList<ScoringEvaluation.Ending>();
        for (var playerCount : model.playerCounts()) {
            var plan = new Plan(model, playerCount);
            var tally = IntStream.range(0, model.gamesPerPlayerCount())
                    .parallel()
                    .collect(() -> new long[TALLY_SIZE], plan::play, ScoringEvaluator::add);
            model.engine().factions().forEach(engine -> cells.add(cell(playerCount, engine.faction(), tally)));
            endings.add(ending(playerCount, model.gamesPerPlayerCount(), tally));
        }
        return new ScoringEvaluation(cells, endings);
    }

    private static void add(long[] into, long[] other) {
        for (var index = 0; index < into.length; index++) {
            into[index] += other[index];
        }
    }

    private static ScoringEvaluation.Cell cell(int playerCount, Faction faction, long[] tally) {
        var present = tally[PRESENT + faction.ordinal()];
        if (present == 0) {
            return new ScoringEvaluation.Cell(playerCount, faction, 0, 0, 0);
        }
        var scale = (double) playerCount / SHARE_UNITS;
        var mean = (double) tally[SHARE_SUM + faction.ordinal()] / present;
        var ratio = mean * scale;
        if (present == 1) {
            return new ScoringEvaluation.Cell(playerCount, faction, ratio, ratio, ratio);
        }
        var meanSquares = (double) tally[SHARE_SQUARES + faction.ordinal()] / present;
        var variance = Math.max(0, meanSquares - mean * mean) * present / (present - 1) * scale * scale;
        var margin = Z_95 * Math.sqrt(variance / present);
        return new ScoringEvaluation.Cell(playerCount, faction, ratio, ratio - margin, ratio + margin);
    }

    private static ScoringEvaluation.Ending ending(int playerCount, int games, long[] tally) {
        var thresholdShare = (double) tally[THRESHOLD_ENDINGS] / games;
        return new ScoringEvaluation.Ending(
                playerCount, (double) tally[ERAS] / games, thresholdShare, 1 - thresholdShare);
    }

    /** What every game of one player count shares. */
    private static final class Plan {

        private final ScoringEngine engine;
        private final long seed;
        private final int playerCount;
        private final List<Faction[]> factionSets;
        private final FactionScoring[] scoring = new FactionScoring[FACTIONS];
        private final double[] winRate = new double[FACTIONS];
        private final double[] winOrMissRate = new double[FACTIONS];
        private final long[] extra = new long[FACTIONS];

        Plan(ScoringModel model, int playerCount) {
            this.engine = model.engine();
            this.seed = model.seed();
            this.playerCount = playerCount;
            engine.factions().forEach(faction -> scoring[faction.faction().ordinal()] = faction);
            model.rates().stream()
                    .filter(rates -> rates.playerCount() == playerCount)
                    .forEach(rates -> {
                        var index = rates.faction().ordinal();
                        winRate[index] = rates.winRate();
                        winOrMissRate[index] = rates.winRate() + rates.missRate();
                        extra[index] = SeatScore.toUnits(rates.extraPointsPerEra());
                    });
            this.factionSets = FactionSets.of(List.of(playerCount)).stream()
                    .filter(set -> set.stream().allMatch(faction -> scoring[faction.ordinal()] != null))
                    .map(set -> set.toArray(new Faction[0]))
                    .toList();
        }

        void play(long[] tally, int game) {
            var random = SplitMix64.seededBy(
                    STREAM, Long.toString(seed), Integer.toString(playerCount), Integer.toString(game));
            var seats = factionSets.get(random.nextInt(factionSets.size()));
            var scores = new SeatScore[seats.length];
            Arrays.setAll(scores, seat -> new SeatScore(scoring[seats[seat].ordinal()]));
            var eras = 0;
            var qualified = false;
            while (eras < engine.maxEras() && !qualified) {
                eras++;
                for (var seat = 0; seat < seats.length; seat++) {
                    var faction = seats[seat].ordinal();
                    scores[seat].apply(outcome(uniform(random), faction));
                    scores[seat].addExtra(extra[faction]);
                }
                qualified = best(scores) >= engine.winThreshold() * SeatScore.UNITS;
            }
            tallyGame(tally, seats, scores, eras, qualified);
        }

        private BetOutcome outcome(double draw, int faction) {
            if (draw < winRate[faction]) {
                return BetOutcome.WON;
            }
            return draw < winOrMissRate[faction] ? BetOutcome.MISSED : BetOutcome.VOIDED;
        }

        private static double uniform(SplitMix64 random) {
            return (random.nextLong() >>> 11) * UNIT;
        }

        private static long best(SeatScore[] scores) {
            var best = Long.MIN_VALUE;
            for (var score : scores) {
                best = Math.max(best, score.total());
            }
            return best;
        }

        private static void tallyGame(long[] tally, Faction[] seats, SeatScore[] scores, int eras, boolean qualified) {
            var best = best(scores);
            var winners = 0;
            for (var score : scores) {
                if (score.total() == best) {
                    winners++;
                }
            }
            var share = SHARE_UNITS / winners;
            for (var seat = 0; seat < seats.length; seat++) {
                var units = scores[seat].total() == best ? share : 0;
                var faction = seats[seat].ordinal();
                tally[PRESENT + faction]++;
                tally[SHARE_SUM + faction] += units;
                tally[SHARE_SQUARES + faction] += (long) units * units;
            }
            tally[ERAS] += eras;
            if (qualified) {
                tally[THRESHOLD_ENDINGS]++;
            }
        }
    }
}
