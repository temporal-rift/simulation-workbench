package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Which game counts for a case: the succeeded attempt's game, or the latest game while the case is unfinished. */
public final class CountingGame {

    private CountingGame() {}

    public static Optional<UUID> of(List<Attempt> attempts) {
        return attempts.stream()
                .filter(attempt -> attempt.state() == AttemptState.SUCCEEDED && attempt.gameId() != null)
                .reduce((first, second) -> second)
                .or(() -> attempts.stream()
                        .filter(attempt -> attempt.gameId() != null)
                        .reduce((first, second) -> second))
                .map(Attempt::gameId);
    }
}
