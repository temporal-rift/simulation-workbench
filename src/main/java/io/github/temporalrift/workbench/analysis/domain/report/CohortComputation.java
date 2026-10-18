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
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;
import io.github.temporalrift.workbench.analysis.domain.statistics.SplitMix64;

/**
 * One cohort's tallies and resampling plans. The report reads its metrics from it, and an evaluation derives further
 * values from the same series under the same plans, so a derived value's interval is its own.
 */
public final class CohortComputation {

    /** A derived value with the status and interval a report metric would carry. */
    public record Estimate(Double value, MetricStatus status, Interval interval) {}

    private final CohortKey key;
    private final List<AnalyzedCase> members;
    private final int eligibleGames;
    private final List<MetricDefinition> definitions;
    private final BlockTallies tallies;
    private final int blocks;
    private final BlockBootstrap bootstrap;
    private final FailureShare failureShare;
    private final List<MetricHeader> failureHeaders;

    private CohortComputation(
            CohortKey key,
            List<AnalyzedCase> members,
            int eligibleGames,
            List<MetricDefinition> definitions,
            BlockTallies tallies,
            BlockBootstrap bootstrap,
            FailureShare failureShare,
            List<MetricHeader> failureHeaders) {
        this.key = key;
        this.members = members;
        this.eligibleGames = eligibleGames;
        this.definitions = definitions;
        this.tallies = tallies;
        this.blocks = tallies.seeds().size();
        this.bootstrap = bootstrap;
        this.failureShare = failureShare;
        this.failureHeaders = failureHeaders;
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
        var members = cases.stream().filter(key::contains).toList();
        var games = new LinkedHashMap<String, List<CaseFacts>>();
        members.stream()
                .filter(AnalyzedCase::succeeded)
                .forEach(member -> Optional.ofNullable(facts.get(member.caseId()))
                        .ifPresent(caseFacts -> games.computeIfAbsent(member.seed(), seed -> new ArrayList<>())
                                .add(caseFacts)));
        var eligible = games.values().stream().mapToInt(List::size).sum();
        var counted = analysis.reportsFailureShare() && key.seatIndex() == null;
        var finished = counted ? members.stream().filter(AnalyzedCase::finished).toList() : List.<AnalyzedCase>of();
        var seeds = new TreeSet<>(BlockTallies.SEED_ORDER);
        seeds.addAll(games.keySet());
        finished.forEach(finishedCase -> seeds.add(finishedCase.seed()));
        var definitions = MetricCatalog.definitions(key, vocabulary);
        var tallies = BlockTallies.collect(definitions, games, key.seatIndex(), seeds);
        var blocks = tallies.seeds().size();
        var bootstrap = blocks < 2
                ? null
                : BlockBootstrap.of(
                        blocks,
                        SplitMix64.seededBy("report", analysis.resamplingKey(), analysis.seedText(), key.text()));
        return new CohortComputation(
                key,
                members,
                eligible,
                definitions,
                tallies,
                bootstrap,
                counted ? FailureShare.collect(tallies.seeds(), finished) : null,
                counted ? FailureShare.headers(failureCodes) : List.of());
    }

    public CohortKey key() {
        return key;
    }

    public int blocks() {
        return blocks;
    }

    /** The cohort as a report lists it. */
    public Cohort cohort() {
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
        for (var header : failureHeaders) {
            metrics.add(ReportCalculator.metric(
                    header, failureSeries(header.dimensions().failureCode()), blocks, bootstrap));
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
        return failureShare == null ? Optional.empty() : Optional.of(failureSeries(code));
    }

    private BlockSeries failureSeries(FailureCode code) {
        return code == null ? failureShare.all() : failureShare.of(code);
    }

    /**
     * Value, status and interval of a statistic that the plans define; {@code NaN} at the point estimate means the
     * cohort has no data for it.
     */
    public Estimate estimate(ToDoubleFunction<int[]> statistic) {
        var point = statistic.applyAsDouble(BlockBootstrap.identity(blocks));
        if (Double.isNaN(point)) {
            return new Estimate(null, MetricStatus.NO_DATA, null);
        }
        if (bootstrap == null) {
            return new Estimate(Precision.round(point), MetricStatus.INSUFFICIENT_SAMPLE, null);
        }
        var interval = bootstrap
                .interval(statistic)
                .map(bounds -> new Interval(Precision.round(bounds[0]), Precision.round(bounds[1])))
                .orElse(null);
        return new Estimate(Precision.round(point), MetricStatus.AVAILABLE, interval);
    }
}
