package io.github.temporalrift.workbench.search.application.query;

import io.github.temporalrift.workbench.search.application.port.in.GetCampaignLogUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.shared.Page;

public class GetCampaignLogQueryHandler implements GetCampaignLogUseCase {

    private final CampaignRepository campaigns;

    public GetCampaignLogQueryHandler(CampaignRepository campaigns) {
        this.campaigns = campaigns;
    }

    @Override
    public Page<LogEntry> handle(Query query) {
        if (campaigns.find(query.campaignId()).isEmpty()) {
            throw new CampaignNotFoundException(query.campaignId());
        }
        return campaigns.log(query.campaignId(), query.limit(), query.offset());
    }
}
