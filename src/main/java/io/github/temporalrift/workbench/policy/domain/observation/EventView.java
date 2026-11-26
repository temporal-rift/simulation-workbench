package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A future event visible to the participant, with its outcomes. {@code stalled} is the public fact that the event is
 * stalled this era, so a bet on it fails.
 */
public record EventView(UUID eventId, List<OutcomeView> outcomes, boolean stalled) {

    public EventView {
        Objects.requireNonNull(eventId, "eventId");
        outcomes = List.copyOf(outcomes);
    }

    /** An event that is not stalled. */
    public EventView(UUID eventId, List<OutcomeView> outcomes) {
        this(eventId, outcomes, false);
    }
}
