package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

public interface SetCampaignParallelismUseCase {

    /**
     * Changes how many games are played at once; games already in flight finish and the next game claimed obeys the
     * new bound.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException for an unknown campaign
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException when the campaign
     *     is over
     */
    Campaign handle(UUID campaignId, int parallelism);
}
