package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.UUID;

public class CampaignNotFoundException extends RuntimeException {

    public CampaignNotFoundException(UUID campaignId) {
        super("No campaign has the id " + campaignId);
    }
}
