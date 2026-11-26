package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;
import java.util.Set;

/**
 * Reasons whose points grow within a run: the n-th occurrence of a run scores {@code base + increment * (n - 1)}, and
 * a seat-era whose bet outcome is in {@code resetOn} ends the run once the era's reasons are scored.
 *
 * @param scoreReasons every occurrence of any of them extends the run
 */
public record Escalation(String name, List<String> scoreReasons, int base, int increment, Set<BetOutcome> resetOn) {

    public Escalation {
        scoreReasons = List.copyOf(scoreReasons);
        resetOn = Set.copyOf(resetOn);
    }
}
