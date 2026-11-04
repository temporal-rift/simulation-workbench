package io.github.temporalrift.workbench.search.application.query;

import java.time.Clock;

import io.github.temporalrift.workbench.search.application.port.in.ListCampaignsUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.shared.Page;

public class ListCampaignsQueryHandler implements ListCampaignsUseCase {

    private final CampaignRepository campaigns;
    private final Clock clock;

    public ListCampaignsQueryHandler(CampaignRepository campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    @Override
    public Page<Campaign> handle(Query query) {
        var now = clock.instant();
        var page = campaigns.list(null, query.limit(), query.offset());
        return new Page<>(
                page.items().stream()
                        .map(stored -> stored.settledAt(now).campaign())
                        .toList(),
                page.total());
    }
}
