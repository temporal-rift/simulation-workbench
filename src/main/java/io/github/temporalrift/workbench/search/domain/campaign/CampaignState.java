package io.github.temporalrift.workbench.search.domain.campaign;

/** Where a campaign stands. {@code CONFIRMING} and {@code COMPLETED} belong to confirmation, not to the search. */
public enum CampaignState {
    QUEUED,
    RUNNING,
    PAUSED,
    SEARCH_COMPLETED,
    CONFIRMING,
    COMPLETED,
    CANCELLED,
    FAILED;

    /** Whether the campaign is over for good: nothing changes it any more. */
    public boolean isTerminal() {
        return this == COMPLETED || this == CANCELLED || this == FAILED;
    }

    /** Whether the search is still ahead of or in the middle of its work. */
    public boolean isSearching() {
        return this == QUEUED || this == RUNNING || this == PAUSED;
    }
}
