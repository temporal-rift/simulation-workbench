package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * One version of the balance criterion: the lines every evaluation applies and the evidence rule. The aggregation is
 * always the weighted sum of squared deviations of the scored lines. A version is never edited; a new rule is a new
 * version.
 *
 * @param digest the SHA-256 of the canonical serialisation, so a change of any value changes it
 */
public record BalanceCriterion(
        int version,
        String digest,
        String title,
        String analysisVersion,
        List<CriterionLine> lines,
        CriterionEvidence evidence) {

    public BalanceCriterion {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(analysisVersion, "analysisVersion");
        Objects.requireNonNull(evidence, "evidence");
        lines = List.copyOf(lines);
        if (lines.stream().map(CriterionLine::id).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("Criterion line ids must be unique");
        }
    }

    /** Every policy some line names. */
    public Set<String> policies() {
        var policies = new LinkedHashSet<String>();
        lines.forEach(line -> policies.addAll(line.policies()));
        return policies;
    }
}
