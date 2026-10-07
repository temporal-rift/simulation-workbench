package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** A future event visible to the participant, with its outcomes. */
public record EventView(UUID eventId, List<OutcomeView> outcomes) {

    public EventView {
        Objects.requireNonNull(eventId, "eventId");
        outcomes = List.copyOf(outcomes);
    }
}
