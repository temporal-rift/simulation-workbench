package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The single frozen input a policy decides from. It can only hold facts the seat is entitled to:
 * its own faction, the visible events, the other participants' public identifiers, and the open
 * window. Observer evidence, opposing credentials or hands, and execution-control state have no
 * field here by construction.
 */
public record EntitledObservation(
        int seatIndex, Faction faction, List<EventView> events, List<UUID> otherPlayerIds, DecisionWindow window) {

    public EntitledObservation {
        if (seatIndex < 0) {
            throw new IllegalArgumentException("seatIndex must not be negative");
        }
        Objects.requireNonNull(faction, "faction");
        Objects.requireNonNull(window, "window");
        events = List.copyOf(events);
        otherPlayerIds = List.copyOf(otherPlayerIds);
    }
}
