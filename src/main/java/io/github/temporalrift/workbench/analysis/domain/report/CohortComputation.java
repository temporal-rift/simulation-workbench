package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;
import io.github.temporalrift.workbench.analysis.domain.statistics.SplitMix64;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

/**
 * One cohort's tallies and resampling plans. The report reads its metrics from it, and an evaluation derives further
 * values from the same series under the same plans, so a derived value's interval is its own. Game metrics resample
 * the seeds that have games; {@code failure_share} resamples the seeds that have finished cases, so a seed whose
 * cases all failed counts there and nowhere else.
 */
public final class CohortComputation {

    /** A derived value with the status and interval a report metric would carry. */
    public record Estimate(Double value, MetricStatus status, Interval interval) {}

    /** The seed blocks, resampling plans and tallies of {@code failure_share}. */
    private record FailureBlocks(
            FailureShare share, int blocks, BlockBootstrap bootstrap, List<MetricHeader> headers) {}

    private final CohortKey key;
    private final List<AnalyzedCase> members;
    private final int eligibleGames;
    private final List<MetricDefinition> definitions;
    private final BlockTallies tallies;
    private final BlockBootstrap bootstrap;
    private final FailureBlocks failure;

    private CohortComputation(
            CohortKey key,
            List<AnalyzedCase> members,
            int eligibleGames,
            List<MetricDefinition> definitions,
            BlockTallies tallies,
            BlockBootstrap bootstrap,
            FailureBlocks failure) {
        this.key = key;
        this.members = members;
        this.eligibleGames = eligibleGames;
        this.definitions = definitions;
        this.tallies = tallies;
        this.bootstrap = bootstrap;
        this.failure = failure;
    }

    /**
     * @param failureCodes the codes that occur anywhere in the run, which every seat-pooling cohort lists
     */
    public static CohortComputation of(
            CohortKey key,
            List<AnalyzedCase> cases,
            Map<UUID, CaseFacts> facts,
            Vocabulary vocabulary,
            Set<FailureCode> failureCodes,
            AnalysisVersion analysis) {
        return ofMembers(key, cases.stream().filter(key::contains).toList(), facts, vocabulary, failureCodes, analysis);
    }

    /** The same for a cohort whose member cases the caller already selected, in case order. */
    public static CohortComputation ofMembers(
            CohortKey key,
            List<AnalyzedCase> members,
            Map<UUID, CaseFacts> facts,
            Vocabulary vocabulary,
            Set<FailureCode> failureCodes,
            AnalysisVersion analysis) {
        var games = new LinkedHashMap<String, List<CaseFacts>>();
        members.stream()
                .filter(AnalyzedCase::succeeded)
                .forEach(member -> Optional.ofNullable(facts.get(member.caseId()))
                        .ifPresent(caseFacts -> games.computeIfAbsent(member.seed(), seed -> new ArrayList<>())
                                .add(caseFacts)));
        var eligible = games.values().stream().mapToInt(List::size).sum();
        var definitions = MetricCatalog.definitions(key, vocabulary);
        var tallies = BlockTallies.collect(definitions, games, key.seatIndex());
        var stream = SplitMix64.seededBy("report", analysis.resamplingKey(), analysis.seedText(), key.text());
        var bootstrap = resamples(tallies.seeds().size(), stream);
        var failure = analysis.reportsFailureShare() && key.seatIndex() == null
                ? failureBlocks(key, members, failureCodes, analysis)
                : null;
        return new CohortComputation(key, members, eligible, definitions, tallies, bootstrap, failure);
    }

    private static FailureBlocks failureBlocks(
            CohortKey key, List<AnalyzedCase> members, Set<FailureCode> failureCodes, AnalysisVersion analysis) {
        var finished = members.stream().filter(AnalyzedCase::finished).toList();
        var seeds = new TreeSet<>(BlockTallies.SEED_ORDER);
        finished.forEach(finishedCase -> seeds.add(finishedCase.seed()));
        var blocks = List.copyOf(seeds);
        var stream = SplitMix64.seededBy("report", analysis.resamplingKey(), analysis.seedText(), key.text());
        return new FailureBlocks(
                FailureShare.collect(blocks, finished),
                blocks.size(),
                resamples(blocks.size(), stream),
                FailureShare.headers(failureCodes));
    }

    private static BlockBootstrap resamples(int blocks, SplitMix64 stream) {
        return blocks < 2 ? null : BlockBootstrap.of(blocks, stream);
    }

    public CohortKey key() {
        return key;
    }

    /** The cohort as a report lists it. */
    public Cohort cohort() {
        var blocks = tallies.seeds().size();
        var metrics = new ArrayList<Metric>();
        for (var index = 0; index < definitions.size(); index++) {
            var definition = definitions.get(index);
            metrics.add(ReportCalculator.metric(
                    new MetricHeader(
                            definition.name(), definition.statistic(), definition.dimensions(), definition.unit()),
                    tallies.series(index),
                    blocks,
                    bootstrap));
        }
        if (failure != null) {
            failure.headers()
                    .forEach(header -> metrics.add(ReportCalculator.metric(
                            header,
                            failureSeries(header.dimensions().failureCode()),
                            failure.blocks(),
                            failure.bootstrap())));
        }
        return new Cohort(key, CaseCounts.of(members), eligibleGames, blocks, metrics);
    }

    /** The series of a game or seat metric of the catalog, absent when the cohort does not list it. */
    public Optional<BlockSeries> series(String name, Dimensions dimensions) {
        for (var index = 0; index < definitions.size(); index++) {
            var definition = definitions.get(index);
            if (definition.name().equals(name) && definition.dimensions().equals(dimensions)) {
                return Optional.of(tallies.series(index));
            }
        }
        return Optional.empty();
    }

    /**
     * The series of {@code failure_share} for a code, or for every code when null; absent in a cohort that does not
     * pool seats.
     */
    public Optional<BlockSeries> failureShare(FailureCode code) {
        return failure == null ? Optional.empty() : Optional.of(failureSeries(code));
    }

    private BlockSeries failureSeries(FailureCode code) {
        return code == null ? failure.share().all() : failure.share().of(code);
    }

    /**
     * Value, status and interval of a statistic over the game series that the plans define; {@code NaN} at the point
     * estimate means the cohort has no data for it.
     */
    public Estimate estimate(ToDoubleFunction<int[]> statistic) {
        return estimate(statistic, tallies.seeds().size(), bootstrap);
    }

    /** The same for a statistic over the {@code failure_share} series, under the plans of the finished seeds. */
    public Estimate failureEstimate(ToDoubleFunction<int[]> statistic) {
        return failure == null
                ? new Estimate(null, MetricStatus.NO_DATA, null)
                : estimate(statistic, failure.blocks(), failure.bootstrap());
    }

    private static Estimate estimate(ToDoubleFunction<int[]> statistic, int blocks, BlockBootstrap resamples) {
        var point = statistic.applyAsDouble(BlockBootstrap.identity(blocks));
        if (Double.isNaN(point)) {
            return new Estimate(null, MetricStatus.NO_DATA, null);
        }
        if (resamples == null) {
            return new Estimate(Precision.round(point), MetricStatus.INSUFFICIENT_SAMPLE, null);
        }
        var interval = resamples
                .interval(statistic)
                .map(bounds -> new Interval(Precision.round(bounds[0]), Precision.round(bounds[1])))
                .orElse(null);
        return new Estimate(Precision.round(point), MetricStatus.AVAILABLE, interval);
    }
}
