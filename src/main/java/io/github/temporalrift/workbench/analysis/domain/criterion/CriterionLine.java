package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.List;
import java.util.Objects;

/**
 * One metric with its dimension, the populations and player counts whose cohorts it covers, a band and a role.
 *
 * @param weight greater than 0 for a scored line; null for a gate
 */
public record CriterionLine(
        String id,
        String metric,
        Derivation derivation,
        CriterionDimension dimension,
        List<CriterionPopulation> populations,
        List<Integer> playerCounts,
        Pooling pooling,
        Band band,
        CriterionRole role,
        Double weight,
        String rationale) {

    public CriterionLine {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(metric, "metric");
        Objects.requireNonNull(derivation, "derivation");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(pooling, "pooling");
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(rationale, "rationale");
        populations = List.copyOf(populations);
        playerCounts = List.copyOf(playerCounts);
        if (populations.isEmpty() || playerCounts.isEmpty()) {
            throw new IllegalArgumentException("Line " + id + " needs a population and a player count");
        }
        if ((role == CriterionRole.SCORED) != (weight != null && weight > 0)) {
            throw new IllegalArgumentException(
                    "Line " + id + ": a scored line needs a positive weight and a gate needs none");
        }
    }
}
