package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.EndReason;

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

    /** The end offset of every source partition as the last drain read it; empty for sources without offsets. */
    Map<EvidenceLedger.SourcePartition, Long> endOffsets();

    /**
     * Whether the read side has applied every record up to {@link #endOffsets()}; true when the observer has no
     * broker to ask.
     */
    boolean readSideApplied();

    /** The terminal fact observed so far, if the game's end was published. */
    Optional<GameEnded> gameEnded();

    @Override
    void close();

    /** The authoritative ending with the final score of every participant. */
    record GameEnded(EndReason endReason, Map<UUID, Integer> finalScores) {
        public GameEnded {
            finalScores = Map.copyOf(finalScores);
        }
    }
}
