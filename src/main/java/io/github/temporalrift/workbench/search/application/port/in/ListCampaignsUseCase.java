package io.github.temporalrift.workbench.search.application.port.in;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.shared.Page;

public interface ListCampaignsUseCase {

    /** Campaigns newest first, each with the active time it has spent up to now. */
    Page<Campaign> handle(Query query);

    record Query(int limit, int offset) {}
}
