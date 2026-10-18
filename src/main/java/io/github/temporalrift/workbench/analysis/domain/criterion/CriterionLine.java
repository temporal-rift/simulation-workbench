package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.List;
import java.util.Objects;

/**
 * One metric with its dimension, the cohorts it covers, a band and a role.
 *
 * @param weight greater than 0 for a scored line; null for a gate
 */
public record CriterionLine(
        String id,
        String metric,
        Derivation derivation,
        CriterionDimension dimension,
        List<String> policies,
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
        policies = List.copyOf(policies);
        playerCounts = List.copyOf(playerCounts);
        if (policies.isEmpty() || playerCounts.isEmpty()) {
            throw new IllegalArgumentException("Line " + id + " needs a policy and a player count");
        }
        if ((role == CriterionRole.SCORED) != (weight != null && weight > 0)) {
            throw new IllegalArgumentException(
                    "Line " + id + ": a scored line needs a positive weight and a gate needs none");
        }
    }
}
