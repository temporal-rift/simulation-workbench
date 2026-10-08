package io.github.temporalrift.workbench.experiment;

import java.util.List;
import java.util.UUID;

/** One case coordinate of a frozen experiment, identified by its deterministic {@code caseKey}. */
public record ExperimentCase(
        UUID caseKey, String seed, String variantLabel, int playerCount, List<ExperimentSeat> seats) {

    public ExperimentCase {
        seats = List.copyOf(seats);
    }
}
