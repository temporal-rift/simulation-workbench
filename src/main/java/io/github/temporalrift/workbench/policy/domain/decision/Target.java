package io.github.temporalrift.workbench.policy.domain.decision;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;

/** A fully specified target, one variant per published submission shape. */
public sealed interface Target {

    /** Decoy: declares the category shown publicly in place of its own. */
    record Disguise(CardCategory category) implements Target {}

    record EventOutcome(UUID eventId, UUID outcomeId) implements Target {}

    /** Two distinct outcomes of one event: a Swing's source and destination, or a Collide's pair. */
    record OutcomePair(UUID eventId, UUID sourceOutcomeId, UUID targetOutcomeId) implements Target {}

    record Events(List<UUID> eventIds) implements Target {
        public Events {
            eventIds = List.copyOf(eventIds);
        }
    }

    record Player(UUID playerId) implements Target {}

    record Players(List<UUID> playerIds) implements Target {
        public Players {
            playerIds = List.copyOf(playerIds);
        }
    }
}
