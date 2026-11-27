package io.github.temporalrift.workbench.search.domain.campaign;

/** What a campaign searches. */
public enum CampaignKind {

    /** The rules of a baseline, against the balance criterion. */
    RULES_SEARCH,

    /**
     * The parameters of one seat's {@code faction-search} policy for one faction at one player count, against
     * {@code faction-spread} seats, to find a strategy that wins disproportionately.
     */
    POLICY_HUNT
}
