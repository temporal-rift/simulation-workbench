package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;

/** Runs, their cases and the counting game of each succeeded case. */
public interface RunSource {

    /** The run's frozen experiment, or empty for an unknown run. */
    Optional<UUID> experimentOf(UUID runId);

    /** Every case of the run in matrix order. */
    List<AnalyzedCase> cases(UUID runId);

    /** The counting game of a succeeded case, or empty when the case has not succeeded. */
    Optional<GameRecord> countingGame(UUID runId, AnalyzedCase analyzedCase);
}
