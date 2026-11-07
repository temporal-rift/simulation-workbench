package io.github.temporalrift.workbench.analysis.domain.criterion;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
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
        List<CriterionTable> tables,
        List<CriterionLine> lines,
        CriterionEvidence evidence) {

    public BalanceCriterion {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(analysisVersion, "analysisVersion");
        Objects.requireNonNull(evidence, "evidence");
        tables = List.copyOf(tables);
        lines = List.copyOf(lines);
        if (tables.stream().map(CriterionTable::id).distinct().count() != tables.size()) {
            throw new IllegalArgumentException("Criterion table ids must be unique");
        }
        var known = tables.stream().map(CriterionTable::id).collect(java.util.stream.Collectors.toSet());
        lines.forEach(line -> line.populations().forEach(population -> {
            if (!known.contains(population.table())) {
                throw new IllegalArgumentException(
                        "Line " + line.id() + " names the unknown table " + population.table());
            }
        }));
        if (lines.stream().map(CriterionLine::id).distinct().count() != lines.size()) {
            throw new IllegalArgumentException("Criterion line ids must be unique");
        }
    }

    /** Every policy reference some seat of some table names. */
    public Set<String> policies() {
        var policies = new LinkedHashSet<String>();
        tables.forEach(table -> policies.addAll(table.seats()));
        return policies;
    }

    /** The tables a phase plays, in criterion order. */
    public List<CriterionTable> tables(CriterionPhase phase) {
        return tables.stream().filter(table -> table.phases().contains(phase)).toList();
    }

    /**
     * The id of the criterion table that cases played on a table belong to. That is the table of the same id, and for
     * a table the criterion does not name, which a run frozen before seat tables named for its one policy, the first
     * uniform criterion table of that policy.
     *
     * @param policy the criterion reference of the policy on every seat; null when the seats play more than one
     */
    public Optional<String> tableOf(String tableId, String policy) {
        if (tables.stream().anyMatch(table -> table.id().equals(tableId))) {
            return Optional.of(tableId);
        }
        return tables.stream()
                .filter(table -> policy != null
                        && table.uniform()
                        && table.seats().getFirst().equals(policy))
                .map(CriterionTable::id)
                .findFirst();
    }

    /**
     * Whether a cohort belongs to a population: it was played on the population's table and by the population's
     * policy on every seat.
     *
     * @param cohortPolicy the criterion reference of the policy on every seat of the cohort; null when its seats play
     *     more than one
     */
    public boolean covers(CriterionPopulation population, String cohortTable, String cohortPolicy) {
        return cohortPolicy != null
                && population.policy().equals(cohortPolicy)
                && tableOf(cohortTable, cohortPolicy)
                        .filter(population.table()::equals)
                        .isPresent();
    }
}
