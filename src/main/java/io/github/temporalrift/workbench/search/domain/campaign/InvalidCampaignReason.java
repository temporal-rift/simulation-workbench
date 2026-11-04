package io.github.temporalrift.workbench.search.domain.campaign;

/** Why a campaign definition was refused, as the published contract names it. */
public enum InvalidCampaignReason {
    UNKNOWN_PARAMETER_SPACE,
    UNKNOWN_BASELINE,
    UNKNOWN_CRITERION,
    EQUAL_MASTER_SEEDS,
    INVALID_VALUE
}
