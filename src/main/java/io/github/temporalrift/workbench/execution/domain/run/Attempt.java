package io.github.temporalrift.workbench.execution.domain.run;

import java.time.Instant;
import java.util.UUID;

/** One execution or recovery of a logical case; failed and interrupted attempts are retained. */
public record Attempt(
        UUID attemptId,
        UUID caseId,
        int ordinal,
        AttemptState state,
        UUID gameId,
        String laneId,
        Instant startedAt,
        Instant finishedAt,
        Failure failure) {}
