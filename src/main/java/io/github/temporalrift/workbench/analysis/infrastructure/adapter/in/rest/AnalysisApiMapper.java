package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import java.math.BigDecimal;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;

import io.github.temporalrift.workbench.analysis.application.port.in.ListComparisonsUseCase;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonCohort;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonResult;
import io.github.temporalrift.workbench.analysis.domain.comparison.ExcludedPair;
import io.github.temporalrift.workbench.analysis.domain.comparison.MetricDifference;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.report.CaseCounts;
import io.github.temporalrift.workbench.analysis.domain.report.Cohort;
import io.github.temporalrift.workbench.analysis.domain.report.Dimensions;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.Metric;
import io.github.temporalrift.workbench.analysis.domain.report.RunReport;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CardGrade;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CardType;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Comparison;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonSide;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonSummary;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ConfidenceInterval;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.DeclaredDifference;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.MetricDimensions;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.MetricStatistic;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.MetricStatus;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PairSeat;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PairedMetricDifference;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParadoxType;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Report;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReportCaseCounts;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SpecialAction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.TerminalEndReason;

/** Maps reports and comparisons onto the published representations. */
final class AnalysisApiMapper {

    private static final BigDecimal LEVEL = new BigDecimal("0.95");

    private AnalysisApiMapper() {}

    static Report report(RunReport report) {
        return new Report()
                .formatVersion(Report.FormatVersionEnum.fromValue(RunReport.FORMAT_VERSION))
                .runId(report.runId())
                .manifestDigest(report.manifestDigest())
                .analysisVersion(report.analysis().version())
                .analysisSeed(report.analysis().seedText())
                .complete(report.complete())
                .caseCounts(counts(report.caseCounts()))
                .cohorts(
                        report.cohorts().stream().map(AnalysisApiMapper::cohort).toList());
    }

    static ComparisonSummary summary(ListComparisonsUseCase.Listed listed) {
        var definition = listed.definition();
        return new ComparisonSummary(
                definition.comparisonId(),
                side(definition.baseline()),
                side(definition.candidate()),
                definition.analysis().version(),
                definition.analysis().seedText(),
                listed.createdAt().atOffset(ZoneOffset.UTC));
    }

    static Comparison comparison(ComparisonResult result) {
        var definition = result.definition();
        return new Comparison()
                .formatVersion(Comparison.FormatVersionEnum.fromValue(ComparisonResult.FORMAT_VERSION))
                .comparisonId(definition.comparisonId())
                .baseline(side(definition.baseline()))
                .candidate(side(definition.candidate()))
                .declaredDifferences(definition.declaredDifferences().stream()
                        .map(difference -> new DeclaredDifference()
                                .field(DeclaredDifference.FieldEnum.fromValue(
                                        difference.field().name()))
                                .baselineDigest(difference.baselineDigest())
                                .candidateDigest(difference.candidateDigest()))
                        .toList())
                .analysisVersion(definition.analysis().version())
                .analysisSeed(definition.analysis().seedText())
                .complete(result.complete())
                .matchedBlocks(result.matchedBlocks())
                .unmatchedCases(result.unmatchedCases())
                .failedCounterparts(result.failedCounterparts())
                .excludedPairs(result.excludedPairs().stream()
                        .map(AnalysisApiMapper::excluded)
                        .toList())
                .cohorts(result.cohorts().stream()
                        .map(AnalysisApiMapper::comparisonCohort)
                        .toList());
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Cohort cohort(
            Cohort cohort) {
        var key = cohort.key();
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Cohort()
                .variantLabel(key.variantLabel())
                .policyId(key.policyId())
                .policyVersion(key.policyVersion())
                .playerCount(io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Cohort
                        .PlayerCountEnum.fromValue(key.playerCount()))
                .factionSet(key.factionSet() == null ? null : factions(key.factionSet()))
                .seatIndex(key.seatIndex())
                .caseCounts(counts(cohort.caseCounts()))
                .eligibleGames(cohort.eligibleGames())
                .independentBlocks(cohort.independentBlocks())
                .metrics(
                        cohort.metrics().stream().map(AnalysisApiMapper::metric).toList());
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Metric metric(
            Metric metric) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Metric()
                .name(metric.name())
                .statistic(MetricStatistic.fromValue(metric.statistic().name()))
                .dimensions(dimensions(metric.dimensions()))
                .value(decimal(metric.value()))
                .numerator(decimal(metric.numerator()))
                .denominator(Math.toIntExact(metric.denominator()))
                .unit(metric.unit())
                .status(MetricStatus.fromValue(metric.status().name()))
                .interval(interval(metric.interval()))
                .unknownCount(Math.toIntExact(metric.unknownCount()));
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonCohort
            comparisonCohort(ComparisonCohort cohort) {
        var key = cohort.key();
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonCohort()
                .policyId(key.policyId())
                .policyVersion(key.policyVersion())
                .playerCount(io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model
                        .ComparisonCohort.PlayerCountEnum.fromValue(key.playerCount()))
                .factionSet(key.factionSet() == null ? null : factions(key.factionSet()))
                .seatIndex(key.seatIndex())
                .matchedBlocks(cohort.matchedBlocks())
                .excludedBlocks(cohort.excludedBlocks())
                .metricDifferences(cohort.differences().stream()
                        .map(AnalysisApiMapper::difference)
                        .toList());
    }

    private static PairedMetricDifference difference(MetricDifference difference) {
        return new PairedMetricDifference()
                .name(difference.name())
                .statistic(MetricStatistic.fromValue(difference.statistic().name()))
                .dimensions(dimensions(difference.dimensions()))
                .unit(difference.unit())
                .baselineValue(decimal(difference.baselineValue()))
                .candidateValue(decimal(difference.candidateValue()))
                .difference(decimal(difference.difference()))
                .status(MetricStatus.fromValue(difference.status().name()))
                .interval(interval(difference.interval()));
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExcludedPair
            excluded(ExcludedPair pair) {
        var seats = new java.util.ArrayList<PairSeat>();
        for (var seat = 0; seat < pair.seats().size(); seat++) {
            seats.add(
                    new PairSeat().seatIndex(seat).faction(faction(pair.seats().get(seat))));
        }
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExcludedPair()
                .seed(pair.seed())
                .playerCount(io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model
                        .ExcludedPair.PlayerCountEnum.fromValue(pair.playerCount()))
                .policyId(pair.policyId())
                .policyVersion(pair.policyVersion())
                .seats(seats)
                .baselineCaseId(pair.baselineCaseId())
                .candidateCaseId(pair.candidateCaseId())
                .baselineState(CaseState.fromValue(pair.baselineState().name()))
                .candidateState(CaseState.fromValue(pair.candidateState().name()))
                .reason(io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExcludedPair
                        .ReasonEnum.fromValue(pair.reason().name()));
    }

    private static ComparisonSide side(
            io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide side) {
        return new ComparisonSide().runId(side.runId()).variantLabel(side.variantLabel());
    }

    private static MetricDimensions dimensions(Dimensions dimensions) {
        var api = new MetricDimensions();
        if (dimensions.faction() != null) {
            api.faction(faction(dimensions.faction()));
        }
        if (dimensions.card() != null) {
            api.cardType(CardType.fromValue(dimensions.card().type().name()))
                    .cardGrade(CardGrade.fromValue(dimensions.card().grade().name()));
        }
        if (dimensions.specialAction() != null) {
            api.specialAction(SpecialAction.fromValue(dimensions.specialAction().name()));
        }
        if (dimensions.paradoxType() != null) {
            api.paradoxType(ParadoxType.fromValue(dimensions.paradoxType().name()));
        }
        if (dimensions.endReason() != null) {
            api.endReason(TerminalEndReason.fromValue(dimensions.endReason().name()));
        }
        if (dimensions.quantile() != null) {
            api.quantile(BigDecimal.valueOf(dimensions.quantile()));
        }
        return api;
    }

    private static ReportCaseCounts counts(CaseCounts counts) {
        return new ReportCaseCounts()
                .requested(counts.requested())
                .pending(counts.pending())
                .running(counts.running())
                .succeeded(counts.succeeded())
                .failed(counts.failed())
                .cancelled(counts.cancelled());
    }

    private static ConfidenceInterval interval(Interval interval) {
        return interval == null
                ? null
                : new ConfidenceInterval()
                        .level(ConfidenceInterval.LevelEnum.fromValue(LEVEL))
                        .lower(BigDecimal.valueOf(interval.lower()))
                        .upper(BigDecimal.valueOf(interval.upper()))
                        .method(ConfidenceInterval.MethodEnum.BLOCK_BOOTSTRAP);
    }

    private static LinkedHashSet<
                    io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction>
            factions(List<Faction> factions) {
        var set = new LinkedHashSet<
                io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction>();
        factions.forEach(faction -> set.add(faction(faction)));
        return set;
    }

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction faction(
            Faction faction) {
        return io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction.fromValue(
                faction.name());
    }

    private static BigDecimal decimal(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }
}
