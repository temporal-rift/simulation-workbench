package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.SeatFacts;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;

/**
 * Per-seed sums of every metric of a cohort. A seed is the independent block: cases sharing a seed share their
 * gameplay randomness across faction sets, rotations and variants.
 */
public final class BlockTallies {

    /** Seeds compare as unsigned decimal integers. */
    public static final Comparator<String> SEED_ORDER =
            Comparator.comparingInt(String::length).thenComparing(Comparator.naturalOrder());

    private final List<String> seeds;
    private final List<BlockSeries> series;

    private BlockTallies(List<String> seeds, List<BlockSeries> series) {
        this.seeds = seeds;
        this.series = series;
    }

    /**
     * @param games each eligible game's facts keyed by its seed
     * @param seatIndex the cohort's seat, or null to count every seat
     */
    public static BlockTallies collect(
            List<MetricDefinition> definitions, Map<String, List<CaseFacts>> games, Integer seatIndex) {
        var bySeed = new TreeMap<String, List<CaseFacts>>(SEED_ORDER);
        bySeed.putAll(games);
        var seeds = List.copyOf(bySeed.keySet());
        var series = new ArrayList<BlockSeries>();
        definitions.forEach(definition -> series.add(new BlockSeries(seeds.size())));
        var block = 0;
        for (var blockGames : bySeed.values()) {
            for (var facts : blockGames) {
                var seats = scope(facts, seatIndex);
                for (var metric = 0; metric < definitions.size(); metric++) {
                    add(series.get(metric), block, definitions.get(metric).measure(), facts, seats);
                }
            }
            block++;
        }
        return new BlockTallies(seeds, series);
    }

    public List<String> seeds() {
        return seeds;
    }

    public BlockSeries series(int metric) {
        return series.get(metric);
    }

    private static List<SeatFacts> scope(CaseFacts facts, Integer seatIndex) {
        return seatIndex == null ? facts.seats() : List.of(facts.seats().get(seatIndex));
    }

    private static void add(
            BlockSeries series, int block, MetricDefinition.Measure measure, CaseFacts facts, List<SeatFacts> seats) {
        switch (measure) {
            case MetricDefinition.Measure.Ratio(var function) -> {
                var tally = function.of(facts, seats);
                series.addRatio(block, tally.numerator(), tally.denominator(), tally.unknown());
            }
            case MetricDefinition.Measure.Values(var function) ->
                function.values(facts, seats, value -> series.addValue(block, value));
        }
    }
}
