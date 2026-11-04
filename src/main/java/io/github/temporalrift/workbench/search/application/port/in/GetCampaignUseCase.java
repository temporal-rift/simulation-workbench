package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

public interface GetCampaignUseCase {

    /**
     * The campaign with the active time it has spent up to now.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException for an unknown campaign
     */
    Campaign handle(UUID campaignId);
}
