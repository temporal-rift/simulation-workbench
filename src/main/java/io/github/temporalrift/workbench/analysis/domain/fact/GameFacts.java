package io.github.temporalrift.workbench.analysis.domain.fact;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;

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
        int distinctCascadedEvents) {

    public GameFacts {
        Objects.requireNonNull(endReason, "endReason");
        findings = Collections.unmodifiableMap(new TreeMap<>(findings));
    }

    public int totalFindings() {
        return findings.values().stream().mapToInt(Integer::intValue).sum();
    }
}
