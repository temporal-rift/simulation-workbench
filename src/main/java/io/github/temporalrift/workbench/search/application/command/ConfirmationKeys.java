package io.github.temporalrift.workbench.search.application.command;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Idempotency keys of the experiments and runs of a confirmation, so asking again converges on the same ones. */
final class ConfirmationKeys {

    private ConfirmationKeys() {}

    static UUID experiment(UUID campaignId, String subject) {
        return of(campaignId, "experiment", subject);
    }

    static UUID run(UUID campaignId, String subject) {
        return of(campaignId, "run", subject);
    }

    private static UUID of(UUID campaignId, String purpose, String subject) {
        return UUID.nameUUIDFromBytes(
                (campaignId + ":confirmation-" + purpose + ":" + subject).getBytes(StandardCharsets.UTF_8));
    }
}
