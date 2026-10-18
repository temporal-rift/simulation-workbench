package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.AttemptFailureCode;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.BalanceCriterionCatalog;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Band;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ConfirmationSample;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionAggregation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionDerivation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionDimension;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionEvidence;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionLine;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionPooling;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CriterionRole;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluatedLine;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluatedLineDimensions;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluationMode;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluationValidity;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluationValidityReason;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluationVerdict;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.LineVerdict;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.MetricStatus;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.TerminalEndReason;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ValidityReasonCode;

/**
 * Maps criteria and evaluations to the published contract. Domain types are reached through accessors only, because
 * the contract models share their names.
 */
final class EvaluationApiMapper {

    private EvaluationApiMapper() {}

    static BalanceCriterionCatalog catalog(List<BalanceCriterion> criteria) {
        return new BalanceCriterionCatalog(
                criteria.stream().map(EvaluationApiMapper::criterion).toList());
    }

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.BalanceCriterion criterion(
            BalanceCriterion criterion) {
        var evidence = criterion.evidence();
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.BalanceCriterion()
                .version(criterion.version())
                .digest(criterion.digest())
                .title(criterion.title())
                .analysisVersion(criterion.analysisVersion())
                .lines(criterion.lines().stream().map(EvaluationApiMapper::line).toList())
                .aggregation(CriterionAggregation.WEIGHTED_SUM_OF_SQUARED_DEVIATIONS)
                .evidence(new CriterionEvidence()
                        .confidenceLevel(CriterionEvidence.ConfidenceLevelEnum.NUMBER_0_DOT_95)
                        .executionFailedShareLimit(AnalysisApiMapper.decimal(evidence.executionFailedShareLimit()))
                        .invalidatingFailureCodes(evidence.invalidatingFailureCodes().stream()
                                .sorted()
                                .map(code -> AttemptFailureCode.fromValue(code.name()))
                                .toList())
                        .invalidatingEndReasons(evidence.invalidatingEndReasons().stream()
                                .sorted()
                                .map(reason -> TerminalEndReason.fromValue(reason.name()))
                                .toList())
                        .confirmationMinimumSeeds(evidence.confirmationMinimumSeeds()));
    }

    private static CriterionLine line(io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine line) {
        var dimension = line.dimension();
        return new CriterionLine()
                .id(line.id())
                .metric(line.metric())
                .derivation(CriterionDerivation.fromValue(line.derivation().name()))
                .dimension(new CriterionDimension()
                        .eachFaction(dimension.eachFaction())
                        .endReason(
                                dimension.endReason() == null
                                        ? null
                                        : CriterionDimension.EndReasonEnum.fromValue(
                                                dimension.endReason().name()))
                        .quantile(AnalysisApiMapper.decimal(dimension.quantile()))
                        .failureCodes(
                                dimension.failureCodes() == null
                                        ? null
                                        : dimension.failureCodes().stream()
                                                .map(code -> AttemptFailureCode.fromValue(code.name()))
                                                .toList()))
                .policies(line.policies())
                .playerCounts(line.playerCounts().stream()
                        .map(CriterionLine.PlayerCountsEnum::fromValue)
                        .toList())
                .pooling(CriterionPooling.fromValue(line.pooling().name()))
                .band(band(line.band()))
                .role(CriterionRole.fromValue(line.role().name()))
                .weight(AnalysisApiMapper.decimal(line.weight()))
                .rationale(line.rationale());
    }

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RunEvaluation evaluation(
            RunEvaluation evaluation) {
        var sample = evaluation.confirmationSample();
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RunEvaluation()
                .runId(evaluation.runId())
                .variantLabel(evaluation.variantLabel())
                .criterionVersion(evaluation.criterionVersion())
                .criterionDigest(evaluation.criterionDigest())
                .analysisVersion(evaluation.analysis().version())
                .analysisSeed(evaluation.analysis().seedText())
                .mode(EvaluationMode.fromValue(evaluation.mode().name()))
                .validity(new EvaluationValidity()
                        .valid(evaluation.validity().valid())
                        .reasons(evaluation.validity().reasons().stream()
                                .map(reason -> new EvaluationValidityReason()
                                        .code(ValidityReasonCode.fromValue(
                                                reason.code().name()))
                                        .detail(reason.detail()))
                                .toList()))
                .verdict(EvaluationVerdict.fromValue(evaluation.verdict().name()))
                .score(evaluation.score() == null ? null : AnalysisApiMapper.decimal(evaluation.score()))
                .confirmationSample(
                        sample == null
                                ? null
                                : new ConfirmationSample()
                                        .requiredSeeds(sample.requiredSeeds())
                                        .seeds(sample.seeds())
                                        .fullMatrix(sample.fullMatrix())
                                        .satisfied(sample.satisfied()))
                .lines(evaluation.lines().stream()
                        .map(EvaluationApiMapper::evaluatedLine)
                        .toList());
    }

    private static EvaluatedLine evaluatedLine(
            io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluatedLine line) {
        var dimensions = line.dimensions();
        return new EvaluatedLine()
                .lineId(line.lineId())
                .role(CriterionRole.fromValue(line.role().name()))
                .metric(line.metric())
                .dimensions(new EvaluatedLineDimensions()
                        .faction(
                                dimensions.faction() == null
                                        ? null
                                        : io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1
                                                .model.Faction.fromValue(
                                                dimensions.faction().name()))
                        .endReason(
                                dimensions.endReason() == null
                                        ? null
                                        : EvaluatedLineDimensions.EndReasonEnum.fromValue(
                                                dimensions.endReason().name()))
                        .quantile(AnalysisApiMapper.decimal(dimensions.quantile()))
                        .failureCodes(
                                dimensions.failureCodes() == null
                                        ? null
                                        : dimensions.failureCodes().stream()
                                                .map(code -> AttemptFailureCode.fromValue(code.name()))
                                                .collect(Collectors.toList())))
                .policyId(line.policyId())
                .playerCount(EvaluatedLine.PlayerCountEnum.fromValue(line.playerCount()))
                .factionSet(line.factionSet() == null ? null : AnalysisApiMapper.factions(line.factionSet()))
                .band(band(line.band()))
                .value(AnalysisApiMapper.decimal(line.value()))
                .interval(AnalysisApiMapper.interval(line.interval()))
                .status(MetricStatus.fromValue(line.status().name()))
                .deviation(BigDecimal.valueOf(line.deviation()))
                .verdict(LineVerdict.fromValue(line.verdict().name()))
                .weight(AnalysisApiMapper.decimal(line.weight()));
    }

    private static Band band(io.github.temporalrift.workbench.analysis.domain.criterion.Band band) {
        return new Band()
                .lower(AnalysisApiMapper.decimal(band.lower()))
                .upper(AnalysisApiMapper.decimal(band.upper()))
                .scale(AnalysisApiMapper.decimal(band.scale()));
    }
}
