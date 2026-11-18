package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.shared.game.EndReason;

/**
 * What a succeeded game was as a whole. Findings and cascaded events are different units: one cascade can carry
 * several findings, and an event cascaded twice counts once.
 */
public record GameFacts(
        EndReason endReason,
        int winners,
        int eras,
        int rounds,
        int decisions,
        Map<ParadoxType, Integer> findings,
        int resolvedFindings,
        int cascadedFindings,
        int distinctCascadedEvents,
        Map<ParadoxType, Integer> cascadedFindingsByType) {

    public GameFacts {
        Objects.requireNonNull(endReason, "endReason");
        findings = Collections.unmodifiableMap(new TreeMap<>(findings));
        // Facts stored before the per-type cascade count existed read it as empty.
        cascadedFindingsByType = cascadedFindingsByType == null
                ? Map.of()
                : Collections.unmodifiableMap(new TreeMap<>(cascadedFindingsByType));
    }

    /** Game facts without the per-type cascade count. */
    public GameFacts(
            EndReason endReason,
            int winners,
            int eras,
            int rounds,
            int decisions,
            Map<ParadoxType, Integer> findings,
            int resolvedFindings,
            int cascadedFindings,
            int distinctCascadedEvents) {
        this(
                endReason,
                winners,
                eras,
                rounds,
                decisions,
                findings,
                resolvedFindings,
                cascadedFindings,
                distinctCascadedEvents,
                Map.of());
    }

    public int totalFindings() {
        return findings.values().stream().mapToInt(Integer::intValue).sum();
    }
}
