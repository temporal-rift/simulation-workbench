package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

public interface ResumeCampaignUseCase {

    /**
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException for an unknown campaign
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException when the campaign
     *     is in a state that does not allow it
     */
    Campaign handle(UUID campaignId, UUID idempotencyKey);
}
