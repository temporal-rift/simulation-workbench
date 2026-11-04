package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.shared.Page;

public interface GetCampaignLogUseCase {

    /**
     * The campaign decision log in sequence order.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException for an unknown campaign
     */
    Page<LogEntry> handle(Query query);

    record Query(UUID campaignId, int limit, int offset) {}
}
