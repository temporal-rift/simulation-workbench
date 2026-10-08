package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/** One real game on a lane, observed and driven only through participant operations and barriers. */
public interface GameSession {

    UUID gameId();

    /** The seats' participant operations for this game. */
    ParticipantGateway participants();

    /** Where the game stands once the services and projection have settled. */
    GameProgress poll();

    /**
     * Advances the coordinated logical clock to the next authoritative deadline on both services and
     * waits for the resulting service, projection and observer barriers.
     */
    void advanceClock();

    /**
     * The authoritative ending, winners, reveal and final scores, once all of them are reconciled for
     * this game; empty while any evidence is still missing.
     */
    Optional<AuthoritativeEnding> ending();

    record AuthoritativeEnding(EndReason endReason, List<Winner> winners, List<FinalScore> finalScores, int eras) {
        public AuthoritativeEnding {
            winners = List.copyOf(winners);
            finalScores = List.copyOf(finalScores);
        }
    }
}
