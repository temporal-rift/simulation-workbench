package io.github.temporalrift.workbench.search.application.query;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.GetCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;

public class GetCampaignQueryHandler implements GetCampaignUseCase {

    private final CampaignRepository campaigns;
    private final Clock clock;

    public GetCampaignQueryHandler(CampaignRepository campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    @Override
    public Campaign handle(UUID campaignId) {
        return campaigns
                .find(campaignId)
                .map(stored -> stored.settledAt(clock.instant()).campaign())
                .orElseThrow(() -> new CampaignNotFoundException(campaignId));
    }
}
