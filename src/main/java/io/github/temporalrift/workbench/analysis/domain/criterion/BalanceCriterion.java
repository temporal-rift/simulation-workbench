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
        var known = tables.stream().collect(java.util.stream.Collectors.toMap(CriterionTable::id, table -> table));
        lines.forEach(line -> line.populations().forEach(population -> {
            var table = known.get(population.table());
            if (table == null) {
                throw new IllegalArgumentException(
                        "Line " + line.id() + " names the unknown table " + population.table());
            }
            if (!table.seats().contains(population.policy())) {
                throw new IllegalArgumentException("Line " + line.id() + " names the policy " + population.policy()
                        + ", which no seat of table " + table.id() + " plays");
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

    /** The table of an id the criterion names. */
    public Optional<CriterionTable> table(String id) {
        return tables.stream().filter(table -> table.id().equals(id)).findFirst();
    }

    /**
     * The criterion table that cases played on a run's table belong to: the table {@link #tableOf} maps it to, when
     * each seat of the cases plays the policy that criterion table names for that seat.
     *
     * @param seatPolicies the criterion reference of the policy on each seat of the cases, by seat index
     */
    public Optional<CriterionTable> tableOfSeats(String tableId, List<String> seatPolicies) {
        var first = seatPolicies.getFirst();
        var uniform = seatPolicies.stream().allMatch(first::equals) ? first : null;
        return tableOf(tableId, uniform).flatMap(this::table).filter(table -> table.seatsPlay(seatPolicies));
    }
}
