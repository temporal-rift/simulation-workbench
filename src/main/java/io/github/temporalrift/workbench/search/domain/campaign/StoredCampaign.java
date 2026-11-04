package io.github.temporalrift.workbench.search.domain.campaign;

import java.time.Duration;
import java.time.Instant;

/**
 * A campaign as stored: its row version, which a write must present to prove nothing else wrote since, and the
 * moment its active time was last counted up to.
 */
public record StoredCampaign(Campaign campaign, long version, Instant accruedAt) {

    /**
     * The campaign with the whole seconds of active time since it was last counted, up to {@code now}, added to what
     * it has spent, and the instant they are counted to. Only a running campaign spends time; the part of a second
     * not yet counted stays for the next count.
     */
    public Settled settledAt(Instant now) {
        if (campaign.state() != CampaignState.RUNNING) {
            return new Settled(campaign, accruedAt);
        }
        var whole = Math.max(0, Duration.between(accruedAt, now).toSeconds());
        return new Settled(whole == 0 ? campaign : campaign.spend(whole), accruedAt.plusSeconds(whole));
    }

    /** A campaign with its active time counted up to {@code accruedAt}. */
    public record Settled(Campaign campaign, Instant accruedAt) {}
}
