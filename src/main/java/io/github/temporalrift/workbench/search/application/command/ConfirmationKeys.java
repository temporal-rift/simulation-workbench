package io.github.temporalrift.workbench.search.application.command;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Idempotency keys of the experiments and runs of a confirmation, so asking again converges on the same ones. */
final class ConfirmationKeys {

    private ConfirmationKeys() {}

    static UUID experiment(UUID campaignId, String subject) {
        return of(campaignId, "experiment", subject);
    }

    /** A run plays one experiment, so its key names the experiment as well as the subject. */
    static UUID run(UUID campaignId, String subject, UUID experimentId) {
        return of(campaignId, "run", subject + ":" + experimentId);
    }

    private static UUID of(UUID campaignId, String purpose, String subject) {
        return UUID.nameUUIDFromBytes(
                (campaignId + ":confirmation-" + purpose + ":" + subject).getBytes(StandardCharsets.UTF_8));
    }
}
