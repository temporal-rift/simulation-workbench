package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignClaim;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCreation;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignDefaults;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.search.domain.port.out.MasterSeeds;
import io.github.temporalrift.workbench.shared.text.JsonText;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * Accepts a campaign: whatever the request leaves out is resolved first (the newest records the service ships and two
 * random master seeds), then the definition must be searchable on the served parameter space, baseline and criterion,
 * and the two master seeds must differ. The campaign is stored queued with every resolved value and starts without
 * further action; whether the simulator may be searched on is the worker concern, not a reason to refuse the
 * definition.
 */
public class CreateCampaignCommandHandler implements CreateCampaignUseCase {

    private static final String SUPPORTED_ALGORITHM = "SUCCESSIVE_HALVING_V1";

    private final CampaignRepository campaigns;
    private final CampaignExperiments experiments;
    private final CandidateEvaluation evaluation;
    private final CampaignDefaults defaults;
    private final MasterSeeds masterSeeds;
    private final Clock clock;

    public CreateCampaignCommandHandler(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CandidateEvaluation evaluation,
            CampaignDefaults defaults,
            MasterSeeds masterSeeds,
            Clock clock) {
        this.campaigns = campaigns;
        this.experiments = experiments;
        this.evaluation = evaluation;
        this.defaults = defaults;
        this.masterSeeds = masterSeeds;
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
            case CampaignCreation.Existing(var claim) -> replay(claim, hash, command.idempotencyKey());
        };
    }

    private CampaignDefinition definitionOf(Command command, int budgetSeconds) {
        var supplied = command.searchMasterSeed();
        if (supplied != null && supplied.equals(command.confirmationMasterSeed())) {
            throw new InvalidCampaignException(
                    InvalidCampaignReason.EQUAL_MASTER_SEEDS,
                    "confirmationMasterSeed",
                    "The search and confirmation master seeds must differ");
        }
        var algorithm = algorithmOf(command.algorithm());
        var searchSeed = supplied != null ? supplied : drawnDifferentFrom(command.confirmationMasterSeed());
        var confirmationSeed = command.confirmationMasterSeed() != null
                ? command.confirmationMasterSeed()
                : drawnDifferentFrom(searchSeed);
        try {
            return new CampaignDefinition(
                    command.name(),
                    command.baseline(),
                    command.parameterSpaceVersion() != null
                            ? command.parameterSpaceVersion()
                            : defaults.parameterSpaceVersion(),
                    command.criterionVersion() != null ? command.criterionVersion() : defaults.criterionVersion(),
                    command.policies(),
                    command.playerCounts(),
                    budgetSeconds,
                    searchSeed,
                    confirmationSeed,
                    algorithm);
        } catch (IllegalArgumentException e) {
            throw new InvalidCampaignException(InvalidCampaignReason.INVALID_VALUE, null, e.getMessage());
        }
    }

    /** The approved record's parameters, with each one the designer set in place of the record's. */
    private SearchAlgorithm algorithmOf(AlgorithmRequest request) {
        var approved = defaults.algorithm();
        if (request == null) {
            return approved;
        }
        if (request.version() != null && !SUPPORTED_ALGORITHM.equals(request.version())) {
            throw new InvalidCampaignException(
                    InvalidCampaignReason.INVALID_VALUE,
                    "algorithm.version",
                    "Algorithm " + request.version() + " is not supported");
        }
        try {
            return new SearchAlgorithm(
                    orElse(request.initialCandidates(), approved.initialCandidates()),
                    orElse(request.screeningSeeds(), approved.screeningSeeds()),
                    orElse(request.eta(), approved.eta()),
                    orElse(request.shortListSize(), approved.shortListSize()));
        } catch (IllegalArgumentException e) {
            throw new InvalidCampaignException(InvalidCampaignReason.INVALID_VALUE, "algorithm", e.getMessage());
        }
    }

    private static int orElse(Integer supplied, int approved) {
        return supplied != null ? supplied : approved;
    }

    /** A random seed that is not {@code other}; the designer did not choose it, so a collision is drawn again. */
    private String drawnDifferentFrom(String other) {
        var seed = masterSeeds.next();
        while (seed.equals(other)) {
            seed = masterSeeds.next();
        }
        return seed;
    }

    /** The algorithm settings a request names; the ones it leaves out do not take part. */
    private static Map<String, Object> algorithmIdentity(AlgorithmRequest request) {
        if (request == null) {
            return null;
        }
        var identity = new LinkedHashMap<String, Object>();
        putIfSet(identity, "version", request.version());
        putIfSet(identity, "initialCandidates", request.initialCandidates());
        putIfSet(identity, "screeningSeeds", request.screeningSeeds());
        putIfSet(identity, "eta", request.eta());
        putIfSet(identity, "shortListSize", request.shortListSize());
        return identity;
    }

    private static void putIfSet(Map<String, Object> identity, String key, Object value) {
        if (value != null) {
            identity.put(key, value);
        }
    }

    private static Campaign replay(CampaignClaim claim, String hash, UUID idempotencyKey) {
        if (!claim.requestHash().equals(hash)) {
            throw new CampaignIdempotencyConflictException(idempotencyKey);
        }
        return claim.campaign();
    }

    /**
     * The identity of a request as sent, whatever order its keys came in: only the budget and parallelism defaults are
     * filled in, so an omitted input stays omitted.
     */
    private static String requestHash(Command command, int budgetSeconds, int parallelism) {
        var identity = new LinkedHashMap<String, Object>();
        // The request as sent: an omitted field stays omitted, so a retry hashes the same although the first attempt
        // drew its seeds at random.
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
        identity.put("algorithm", algorithmIdentity(command.algorithm()));
        return Sha256.hexUtf8(JsonText.canonical(identity, "a campaign request"));
    }
}
