package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.stream.Collectors;

import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.shared.Page;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Campaign;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignBaseline;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignFailure;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignList;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogDecision;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogEntry;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogEvidence;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogPage;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogReason;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.EvaluationVerdict;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Policy;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SearchAlgorithm;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SearchAlgorithmRequest;

/**
 * Maps the published campaign representations to and from the domain. The baseline and the policies stay JSON in the
 * domain, as an experiment manifest states them, so they cross the boundary through the JSON mapper.
 */
class CampaignApiMapper {

    private final ObjectMapper mapper;

    CampaignApiMapper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    CreateCampaignUseCase.Command toCommand(java.util.UUID idempotencyKey, CampaignDefinition definition) {
        var playerCounts = definition.getPlayerCounts().stream()
                .map(CampaignDefinition.PlayerCountsEnum::getValue)
                .sorted()
                .toList();
        var algorithm = definition.getAlgorithm();
        return new CreateCampaignUseCase.Command(
                idempotencyKey,
                definition.getName(),
                mapper.valueToTree(definition.getBaseline()),
                definition.getParameterSpaceVersion(),
                definition.getCriterionVersion(),
                mapper.valueToTree(definition.getPolicies()),
                playerCounts,
                definition.getBudgetSeconds(),
                definition.getParallelism(),
                definition.getSearchMasterSeed(),
                definition.getConfirmationMasterSeed(),
                searchAlgorithm(algorithm));
    }

    private static CreateCampaignUseCase.AlgorithmRequest searchAlgorithm(SearchAlgorithmRequest algorithm) {
        if (algorithm == null) {
            return null;
        }
        return new CreateCampaignUseCase.AlgorithmRequest(
                algorithm.getVersion() == null ? null : algorithm.getVersion().getValue(),
                algorithm.getInitialCandidates(),
                algorithm.getScreeningSeeds(),
                algorithm.getEta(),
                algorithm.getShortListSize());
    }

    Campaign toApi(io.github.temporalrift.workbench.search.domain.campaign.Campaign campaign) {
        var definition = campaign.definition();
        var algorithm = definition.algorithm();
        var failure = campaign.failure();
        return new Campaign(
                campaign.campaignId(),
                definition.name(),
                mapper.treeToValue(definition.baseline(), CampaignBaseline.class),
                definition.parameterSpaceVersion(),
                definition.criterionVersion(),
                policies(definition.policies()),
                definition.playerCounts().stream()
                        .map(Campaign.PlayerCountsEnum::fromValue)
                        .collect(Collectors.toCollection(LinkedHashSet::new)),
                definition.budgetSeconds(),
                campaign.parallelism(),
                definition.searchMasterSeed(),
                definition.confirmationMasterSeed(),
                new SearchAlgorithm(
                        SearchAlgorithm.VersionEnum.SUCCESSIVE_HALVING_V1,
                        algorithm.initialCandidates(),
                        algorithm.screeningSeeds(),
                        algorithm.eta(),
                        algorithm.shortListSize()),
                CampaignState.valueOf(campaign.state().name()),
                Math.toIntExact(campaign.spentSeconds()),
                campaign.round(),
                campaign.shortList(),
                null,
                failure == null ? null : new CampaignFailure(failure.code(), failure.message()),
                utc(campaign.createdAt()),
                utc(campaign.startedAt()),
                utc(campaign.finishedAt()));
    }

    private List<Policy> policies(tools.jackson.databind.JsonNode node) {
        var policies = new ArrayList<Policy>();
        node.forEach(policy -> policies.add(mapper.treeToValue(policy, Policy.class)));
        return policies;
    }

    CampaignList toApi(Page<io.github.temporalrift.workbench.search.domain.campaign.Campaign> page) {
        return new CampaignList(page.items().stream().map(this::toApi).toList(), Math.toIntExact(page.total()));
    }

    CampaignLogPage toApiLog(Page<LogEntry> page) {
        return new CampaignLogPage(
                page.items().stream().map(CampaignApiMapper::toApi).toList(), Math.toIntExact(page.total()));
    }

    private static CampaignEvaluation toApi(LogEntry.Evaluation evaluation) {
        if (evaluation == null) {
            return null;
        }
        var score = evaluation.score() == null ? null : BigDecimal.valueOf(evaluation.score());
        return new CampaignEvaluation(EvaluationVerdict.valueOf(evaluation.verdict()), score);
    }

    private static CampaignLogEntry toApi(LogEntry entry) {
        var overrides = new java.util.LinkedHashMap<String, BigDecimal>();
        entry.overrides().forEach(overrides::put);
        return new CampaignLogEntry(
                entry.sequence(),
                entry.round(),
                entry.candidateId(),
                overrides,
                entry.effectiveRulesDigest(),
                new LinkedHashSet<>(entry.seeds()),
                entry.gamesSpent(),
                entry.redraws(),
                toApi(entry.evaluation()),
                CampaignLogDecision.valueOf(entry.decision().name()),
                entry.reason() == null
                        ? null
                        : CampaignLogReason.valueOf(entry.reason().name()),
                evidence(entry.evidence()));
    }

    private static CampaignLogEvidence evidence(LogEntry.Evidence evidence) {
        if (evidence == null) {
            return null;
        }
        var api = new CampaignLogEvidence();
        if (evidence.lineIds() != null) {
            api.setLineIds(evidence.lineIds());
        }
        api.setDominatedBy(evidence.dominatedBy());
        return api;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
