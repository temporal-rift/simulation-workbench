package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.UUID;

/** An idempotency key that a different request already used. */
public class CampaignIdempotencyConflictException extends RuntimeException {

    public CampaignIdempotencyConflictException(UUID idempotencyKey) {
        super("The idempotency key " + idempotencyKey + " was already used for a different request");
    }
}
