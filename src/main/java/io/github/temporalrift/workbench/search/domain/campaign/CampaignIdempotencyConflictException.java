package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.UUID;

/** An idempotency key that already created a campaign from a different definition. */
public class CampaignIdempotencyConflictException extends RuntimeException {

    public CampaignIdempotencyConflictException(UUID idempotencyKey) {
        super("The idempotency key " + idempotencyKey + " already created a campaign from a different request");
    }
}
