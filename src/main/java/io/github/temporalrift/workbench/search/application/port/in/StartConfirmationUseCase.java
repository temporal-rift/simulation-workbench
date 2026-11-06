package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

/** Starts the confirmation of a finished search's short list on fresh seeds. */
public interface StartConfirmationUseCase {

    /**
     * @return the campaign, now confirming
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException for an unknown campaign
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException when the search
     *     is not complete
     * @throws io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException when a confirmation seed
     *     was played by the search; nothing is frozen
     * @throws io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException when no deployed lane can
     *     play the confirmation; nothing stays started
     */
    Campaign handle(UUID campaignId);
}
