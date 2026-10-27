package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * What a lane is: the fit it satisfies, the bundle digests and builds it serves, whether it runs in this process or
 * as a deployment, and the player id of the bot identity that plays each seat.
 *
 * @param botPlayerIds the player id of each bot identity, in seat order
 */
public record LaneProfile(
        LaneFit fit, BundleDigests bundle, BundleProvenance provenance, Kind kind, List<UUID> botPlayerIds) {

    /** Where a lane's services run. */
    public enum Kind {
        /** The simulator: the services run in the workbench's JVM with in-memory adapters. */
        IN_PROCESS,
        /** A deployment of the services with their production adapters. */
        DEPLOYED
    }

    public LaneProfile {
        Objects.requireNonNull(fit, "fit");
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(provenance, "provenance");
        Objects.requireNonNull(kind, "kind");
        botPlayerIds = List.copyOf(botPlayerIds);
    }
}
