package io.github.temporalrift.workbench.search.application.port.in;

import java.util.UUID;
import java.util.function.Supplier;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;

/** Applies a command on an existing campaign once per idempotency key. */
public interface CampaignCommandOnceUseCase {

    /**
     * Applies the command unless its key was claimed. A command that is refused claims nothing, so it can be asked
     * again once the reason is gone.
     *
     * @param apply what the command does
     * @return the campaign the command returned or, on a repeat, the campaign as it stands now
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException when the
     *     key was used for any other request
     */
    Campaign handle(Command command, Supplier<Campaign> apply);

    /**
     * @param operation the name of the operation
     * @param body what the request says beyond its operation and campaign; empty when nothing
     */
    record Command(UUID idempotencyKey, String operation, UUID campaignId, String body) {}
}
