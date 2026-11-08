package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

/** Starts the confirmation of a finished search's short list on fresh seeds. */
public interface StartConfirmationUseCase {

    /**
     * Asked again with the same key after it was accepted, it answers with the campaign and starts nothing.
     *
     * @return the campaign, now confirming
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException for an unknown campaign
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException when the search
     *     is not complete
     * @throws io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException when a confirmation seed
     *     was played by the search; nothing is frozen
     * @throws io.github.temporalrift.workbench.search.domain.campaign.SimulatorNotAvailableException when the
     *     simulator is not enabled, or refuses what the confirmation needs; nothing plays
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException when another
     *     request used the key
     * @throws io.github.temporalrift.workbench.search.domain.campaign.FidelityNotEstablishedException when the
     *     simulator has no passing fidelity record for its source revisions; nothing is frozen
     * @throws io.github.temporalrift.workbench.search.domain.campaign.PolicyNotAvailableException when no bundle
     *     serves a policy the criterion names; nothing is frozen
     */
    Campaign handle(UUID campaignId, UUID idempotencyKey);
}
