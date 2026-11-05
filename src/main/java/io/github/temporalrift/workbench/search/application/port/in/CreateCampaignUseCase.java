package io.github.temporalrift.workbench.search.application.port.in;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

public interface CreateCampaignUseCase {

    /**
     * Freezes the definition into a queued campaign, once per idempotency key: the same key with the same definition
     * returns the original campaign.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException when the definition
     *     cannot be searched
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException when the
     *     key created a campaign from another definition
     */
    Campaign handle(Command command);

    /**
     * The request as the designer made it. Every nullable component stands for "the service decides": the campaign
     * records the value the service resolved for it.
     *
     * @param parameterSpaceVersion the parameter space version, or null for the newest
     * @param criterionVersion the balance criterion version, or null for the newest
     * @param budgetSeconds the active time the search may spend, or null for the default
     * @param parallelism games played at once, or null for the default
     * @param searchMasterSeed the seed that derives every search seed, or null for a random one
     * @param confirmationMasterSeed the seed that derives every confirmation seed, or null for a random one
     * @param algorithm the search parameters the designer sets, or null for the approved record's
     */
    record Command(
            UUID idempotencyKey,
            String name,
            JsonNode baseline,
            Integer parameterSpaceVersion,
            Integer criterionVersion,
            JsonNode policies,
            List<Integer> playerCounts,
            Integer budgetSeconds,
            Integer parallelism,
            String searchMasterSeed,
            String confirmationMasterSeed,
            AlgorithmRequest algorithm) {}

    /**
     * The search parameters a request sets; each null one takes the approved record's value.
     *
     * @param version the algorithm, or null for the one the service implements
     */
    record AlgorithmRequest(
            String version, Integer initialCandidates, Integer screeningSeeds, Integer eta, Integer shortListSize) {}
}
