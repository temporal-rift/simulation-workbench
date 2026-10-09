package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** The facts of one succeeded case under one analysis version, ordered by seat. */
public record CaseFacts(UUID caseId, GameFacts game, List<SeatFacts> seats) {

    public CaseFacts {
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(game, "game");
        seats = List.copyOf(seats);
    }
}
