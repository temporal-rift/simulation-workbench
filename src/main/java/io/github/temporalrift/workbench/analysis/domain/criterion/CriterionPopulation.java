package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.Objects;

/** The seats of one table played by one policy. */
public record CriterionPopulation(String table, String policy) {

    public CriterionPopulation {
        Objects.requireNonNull(table, "table");
        Objects.requireNonNull(policy, "policy");
    }
}
