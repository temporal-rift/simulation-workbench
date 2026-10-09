package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.Collection;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;

/** How many requested cases stand in each state; only succeeded cases are games. */
public record CaseCounts(int requested, int pending, int running, int succeeded, int failed, int cancelled) {

    public static CaseCounts of(Collection<AnalyzedCase> cases) {
        var counts = new int[5];
        cases.forEach(analyzedCase -> counts[analyzedCase.status().ordinal()]++);
        return new CaseCounts(cases.size(), counts[0], counts[1], counts[2], counts[3], counts[4]);
    }

    public boolean complete() {
        return succeeded == requested;
    }
}
