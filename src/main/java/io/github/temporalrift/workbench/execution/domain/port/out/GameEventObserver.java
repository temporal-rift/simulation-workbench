package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Observes the raw events one game publishes on its lane and retains them as evidence. Observation is
 * separate from the bots: nothing it reads ever reaches a policy.
 */
public interface GameEventObserver extends AutoCloseable {

    /**
     * Retains every event published so far.
     *
     * @return whether the observer reached the end of every source, so nothing published earlier is missing
     */
    boolean drain();

    /** The terminal fact observed so far, if the game's end was published. */
    Optional<GameEnded> gameEnded();

    @Override
    void close();

    /** The authoritative ending with the final score of every participant. */
    record GameEnded(String endReason, Map<UUID, Integer> finalScores) {
        public GameEnded {
            finalScores = Map.copyOf(finalScores);
        }
    }
}
