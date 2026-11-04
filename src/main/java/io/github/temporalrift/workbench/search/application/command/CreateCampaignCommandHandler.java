package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCreation;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.shared.text.JsonText;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * Accepts a campaign: the definition must be searchable on the served parameter space, baseline and criterion, and
 * the two master seeds must differ. The campaign is stored queued and starts without further action; whether the
 * simulator may be searched on is the worker concern, not a reason to refuse the definition.
 */
public class CreateCampaignCommandHandler implements CreateCampaignUseCase {

    private final CampaignRepository campaigns;
    private final CampaignExperiments experiments;
    private final CandidateEvaluation evaluation;
    private final Clock clock;

    public CreateCampaignCommandHandler(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CandidateEvaluation evaluation,
            Clock clock) {
        this.campaigns = campaigns;
        this.experiments = experiments;
        this.evaluation = evaluation;
        this.clock = clock;
    }

    @Override
    public Campaign handle(Command command) {
        var budgetSeconds =
                command.budgetSeconds() == null ? CampaignDefinition.DEFAULT_BUDGET_SECONDS : command.budgetSeconds();
        var parallelism =
                command.parallelism() == null ? CampaignDefinition.DEFAULT_PARALLELISM : command.parallelism();
        var hash = requestHash(command, budgetSeconds, parallelism);
        var claimed = campaigns.findClaim(command.idempotencyKey());
        if (claimed.isPresent()) {
            return replay(claimed.get(), hash, command.idempotencyKey());
        }
        var definition = definitionOf(command, budgetSeconds);
        experiments.check(definition);
        if (!evaluation.servesCriterion(definition.criterionVersion())) {
            throw new InvalidCampaignException(
                    InvalidCampaignReason.UNKNOWN_CRITERION,
                    "criterionVersion",
                    "Balance criterion " + definition.criterionVersion() + " is not served");
        }
        var now = clock.instant();
        var queued = Campaign.queued(UUID.randomUUID(), definition, parallelism, now);
        return switch (campaigns.create(command.idempotencyKey(), hash, queued, now)) {
            case CampaignCreation.Created _ -> queued;
            case CampaignCreation.Existing existing -> replay(existing.claim(), hash, command.idempotencyKey());
        };
    }

    private static CampaignDefinition definitionOf(Command command, int budgetSeconds) {
        if (command.searchMasterSeed().equals(command.confirmationMasterSeed())) {
            throw new InvalidCampaignException(
                    InvalidCampaignReason.EQUAL_MASTER_SEEDS,
                    "confirmationMasterSeed",
                    "The search and confirmation master seeds must differ");
        }
        try {
            return new CampaignDefinition(
                    command.name(),
                    command.baseline(),
                    command.parameterSpaceVersion(),
                    command.criterionVersion(),
                    command.policies(),
                    command.playerCounts(),
                    budgetSeconds,
                    command.searchMasterSeed(),
                    command.confirmationMasterSeed(),
                    command.algorithm());
        } catch (IllegalArgumentException e) {
            throw new InvalidCampaignException(InvalidCampaignReason.INVALID_VALUE, null, e.getMessage());
        }
    }

    private static Campaign replay(CampaignClaim claim, String hash, UUID idempotencyKey) {
        if (!claim.requestHash().equals(hash)) {
            throw new CampaignIdempotencyConflictException(idempotencyKey);
        }
        return claim.campaign();
    }

    /** The identity of a request: its definition with the defaults filled in, whatever order its keys came in. */
    private static String requestHash(Command command, int budgetSeconds, int parallelism) {
        var identity = new LinkedHashMap<String, Object>();
        identity.put("name", command.name());
        identity.put("baseline", command.baseline());
        identity.put("parameterSpaceVersion", command.parameterSpaceVersion());
        identity.put("criterionVersion", command.criterionVersion());
        identity.put("policies", command.policies());
        identity.put("playerCounts", command.playerCounts());
        identity.put("budgetSeconds", budgetSeconds);
        identity.put("parallelism", parallelism);
        identity.put("searchMasterSeed", command.searchMasterSeed());
        identity.put("confirmationMasterSeed", command.confirmationMasterSeed());
        identity.put("algorithm", command.algorithm());
        return Sha256.hexUtf8(JsonText.canonical(identity, "a campaign request"));
    }
}
