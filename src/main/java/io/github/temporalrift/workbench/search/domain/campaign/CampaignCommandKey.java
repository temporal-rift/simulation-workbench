package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.UUID;

import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The idempotency key of a command on a campaign and the identity of the request that sent it. A campaign change that
 * carries one claims the key in the same write, so the effect and the claim exist together or not at all.
 *
 * @param requestHash covers the operation, the campaign and what the request says beyond them
 */
public record CampaignCommandKey(UUID idempotencyKey, String requestHash) {

    /**
     * @param body what the request says beyond its operation and campaign; empty when nothing
     */
    public static CampaignCommandKey of(UUID idempotencyKey, String operation, UUID campaignId, String body) {
        return new CampaignCommandKey(idempotencyKey, Sha256.hexUtf8(operation + ":" + campaignId + ":" + body));
    }
}
