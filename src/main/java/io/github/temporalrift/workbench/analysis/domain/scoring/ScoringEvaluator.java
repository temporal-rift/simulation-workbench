package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

import io.github.temporalrift.workbench.analysis.domain.fact.BetReasons;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.FactionSets;
import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * Plays the scoring layer of a game alone: in every era each seat draws exactly one of its bet reasons, a voided bet
 * or no bet from its rates, scores the drawn reason under the engine, adds the expected points of its other reasons,
 * and a points race after {@code maxEras} decides the winners. It never plays cards or resolves events.
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
    private static final int TALLY_SIZE = 3 * FACTIONS;

    private ScoringEvaluator() {}

    public static ScoringEvaluation evaluate(ScoringModel model) {
        var cells = new ArrayList<ScoringEvaluation.Cell>();
        for (var playerCount : model.playerCounts()) {
            var plan = new Plan(model, playerCount);
            var tally = IntStream.range(0, model.gamesPerPlayerCount())
                    .parallel()
                    .collect(() -> new long[TALLY_SIZE], plan::play, ScoringEvaluator::add);
            model.factions().forEach(faction -> cells.add(cell(playerCount, faction, tally)));
        }
        return new ScoringEvaluation(cells);
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

    /** What one faction's seat-era draws from at one player count. */
    private record SeatDraw(
            String[] betReasons, BetOutcome[] outcomes, double[] upTo, double voidedUpTo, long extraUnits) {

        /** The index of the bet reason a uniform draw selects, or -1 for no bet reason. */
        int reason(double draw) {
            for (var index = 0; index < upTo.length; index++) {
                if (draw < upTo[index]) {
                    return index;
                }
            }
            return -1;
        }

        BetOutcome outcome(int reason, double draw) {
            if (reason >= 0) {
                return outcomes[reason];
            }
            return draw < voidedUpTo ? BetOutcome.VOIDED : BetOutcome.NOT_PLACED;
        }
    }

    /** What every game of one player count shares. */
    private static final class Plan {

        private final ScoringEngine engine;
        private final SeatScore.Pricing pricing;
        private final long seed;
        private final int playerCount;
        private final List<Faction[]> factionSets;
        private final SeatDraw[] draws = new SeatDraw[FACTIONS];

        Plan(ScoringModel model, int playerCount) {
            this.engine = model.engine();
            this.pricing = new SeatScore.Pricing(engine);
            this.seed = model.seed();
            this.playerCount = playerCount;
            model.rates().stream()
                    .filter(rates -> rates.playerCount() == playerCount)
                    .forEach(rates -> draws[rates.faction().ordinal()] = draw(rates));
            this.factionSets = FactionSets.of(List.of(playerCount)).stream()
                    .filter(set -> model.factions().containsAll(set))
                    .map(set -> set.toArray(new Faction[0]))
                    .toList();
        }

        private SeatDraw draw(SeatEraRates rates) {
            var reasons = rates.betReasons();
            var names = new String[reasons.size()];
            var outcomes = new BetOutcome[reasons.size()];
            var upTo = new double[reasons.size()];
            var cumulative = 0.0;
            for (var index = 0; index < names.length; index++) {
                names[index] = reasons.get(index).scoreReason();
                outcomes[index] =
                        BetReasons.of(rates.faction()).wonBy(names[index]) ? BetOutcome.WON : BetOutcome.MISSED;
                cumulative += reasons.get(index).rate();
                upTo[index] = cumulative;
            }
            // Expected points are summed exactly and rounded to millionths once, so equal expected scores stay equal
            // however the reasons are split.
            var expected = BigDecimal.ZERO;
            for (var other : rates.otherReasons()) {
                expected = expected.add(BigDecimal.valueOf(other.perEra())
                        .multiply(BigDecimal.valueOf(pricing.flatPoints(other.scoreReason()))));
            }
            var extra = SeatScore.toUnits(expected);
            return new SeatDraw(names, outcomes, upTo, cumulative + rates.voidedRate(), extra);
        }

        void play(long[] tally, int game) {
            var random = SplitMix64.seededBy(
                    STREAM, Long.toString(seed), Integer.toString(playerCount), Integer.toString(game));
            var seats = factionSets.get(random.nextInt(factionSets.size()));
            var scores = new SeatScore[seats.length];
            Arrays.setAll(scores, seat -> new SeatScore(pricing));
            for (var era = 1; era <= engine.maxEras(); era++) {
                for (var seat = 0; seat < seats.length; seat++) {
                    var seatDraw = draws[seats[seat].ordinal()];
                    var draw = uniform(random);
                    var reason = seatDraw.reason(draw);
                    if (reason >= 0) {
                        scores[seat].occurrence(seatDraw.betReasons()[reason]);
                    }
                    scores[seat].addUnits(seatDraw.extraUnits());
                    scores[seat].endEra(seatDraw.outcome(reason, draw));
                }
            }
            tallyGame(tally, seats, scores);
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

        private static void tallyGame(long[] tally, Faction[] seats, SeatScore[] scores) {
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
        }
    }
}
