package io.github.temporalrift.workbench.search.domain.port.out;

import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;

/** What a campaign uses for every input its definition leaves out: the newest approved records the service ships. */
public interface CampaignDefaults {

    /** The search parameters of the newest approved search budget record. */
    SearchAlgorithm algorithm();

    /** The newest parameter space version the service serves. */
    int parameterSpaceVersion();

    /** The newest balance criterion version the service serves. */
    int criterionVersion();
}
