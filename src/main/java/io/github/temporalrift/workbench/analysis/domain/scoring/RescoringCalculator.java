package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import io.github.temporalrift.workbench.analysis.domain.report.BlockTallies;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.random.SplitMix64;

/**
 * Turns the verdicts of a run's cases into win ratios per player count and faction. The independent block of the
 * interval is the seed, as in the run report.
 */
public final class RescoringCalculator {

    private static final String STREAM = "rescoring";

    private RescoringCalculator() {}

    /** One succeeded case and what the engine's race made of it. */
    public record Judgement(int playerCount, String seed, List<Faction> factions, Rescorer.Verdict verdict) {}

    public record Ratio(int playerCount, Faction faction, double ratio) {}

    /** @param resamplingKey what the intervals' resampling derives from, so equal requests give equal intervals */
    public static RescoringResult calculate(boolean complete, String resamplingKey, List<Judgement> judgements) {
        return calculate(complete, resamplingKey, judgements, true);
    }

    /** Calculates neighbor ratios without spending work on intervals that are not returned. */
    public static List<Ratio> ratios(List<Judgement> judgements) {
        return calculate(true, "", judgements, false).cells().stream()
                .map(cell -> new Ratio(cell.playerCount(), cell.faction(), cell.ratio()))
                .toList();
    }

    private static RescoringResult calculate(
            boolean complete, String resamplingKey, List<Judgement> judgements, boolean intervals) {
        var byCount = new TreeMap<Integer, List<Judgement>>();
        judgements.forEach(item -> byCount.computeIfAbsent(item.playerCount(), count -> new ArrayList<>())
                .add(item));
        var cells = new ArrayList<ScoringEvaluation.Cell>();
        var counts = new ArrayList<RescoringResult.Counts>();
        byCount.forEach((playerCount, items) -> {
            var judged = items.stream()
                    .filter(item -> item.verdict() instanceof Rescorer.Verdict.Judged)
                    .toList();
            var eras = judged.stream()
                    .mapToInt(item -> ((Rescorer.Verdict.Judged) item.verdict()).era())
                    .sum();
            counts.add(new RescoringResult.Counts(
                    playerCount,
                    judged.size(),
                    items.size() - judged.size(),
                    judged.isEmpty() ? null : (double) eras / judged.size()));
            cells.addAll(cells(playerCount, resamplingKey, judged, intervals));
        });
        return new RescoringResult(complete, cells, counts);
    }

    private static List<ScoringEvaluation.Cell> cells(
            int playerCount, String resamplingKey, List<Judgement> judged, boolean intervals) {
        var seeds = new TreeSet<>(BlockTallies.SEED_ORDER);
        judged.forEach(item -> seeds.add(item.seed()));
        var blockOf = new TreeMap<String, Integer>(BlockTallies.SEED_ORDER);
        seeds.forEach(seed -> blockOf.put(seed, blockOf.size()));
        var series = new EnumMap<Faction, BlockSeries>(Faction.class);
        for (var item : judged) {
            var block = blockOf.get(item.seed());
            var verdict = (Rescorer.Verdict.Judged) item.verdict();
            for (var seat = 0; seat < item.factions().size(); seat++) {
                series.computeIfAbsent(item.factions().get(seat), faction -> new BlockSeries(blockOf.size()))
                        .addRatio(block, verdict.shares().get(seat), 1, 0);
            }
        }
        var bootstrap = !intervals || blockOf.size() < 2
                ? null
                : BlockBootstrap.of(blockOf.size(), SplitMix64.seededBy(STREAM, resamplingKey, "" + playerCount));
        var cells = new ArrayList<ScoringEvaluation.Cell>();
        for (Map.Entry<Faction, BlockSeries> entry : series.entrySet()) {
            var ratios = entry.getValue();
            var ratio = scale(ratios.totalNumerator(), ratios.totalDenominator(), playerCount);
            var interval = bootstrap == null
                    ? java.util.Optional.<double[]>empty()
                    : bootstrap.interval(plan -> scale(ratios.numerator(plan), ratios.denominator(plan), playerCount));
            cells.add(new ScoringEvaluation.Cell(
                    playerCount,
                    entry.getKey(),
                    ratio,
                    interval.map(bounds -> bounds[0]).orElse(ratio),
                    interval.map(bounds -> bounds[1]).orElse(ratio)));
        }
        return cells;
    }

    /** Win shares in sixtieths over the cases a faction was seated in, as a ratio to an even share; NaN without any. */
    private static double scale(long shareUnits, long seated, int playerCount) {
        return seated == 0 ? Double.NaN : (double) shareUnits / seated / Rescorer.SHARE_UNITS * playerCount;
    }
}
