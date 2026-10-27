package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Collection;

/** How many of a check's cases stand where. */
public record FidelityCounts(int total, int matched, int diverged, int unreproducible, int pending) {

    public static FidelityCounts of(Collection<FidelityCaseResult> results) {
        int matched = 0;
        int diverged = 0;
        int unreproducible = 0;
        int pending = 0;
        for (var result : results) {
            switch (result) {
                case MATCH -> matched++;
                case DIVERGED -> diverged++;
                case UNREPRODUCIBLE -> unreproducible++;
                case PENDING -> pending++;
            }
        }
        return new FidelityCounts(results.size(), matched, diverged, unreproducible, pending);
    }

    /** Whether the check passes: it has at least one case and every case matched. No tolerance exists. */
    public boolean passes() {
        return total > 0 && matched == total;
    }

    /** Whether every case has a result. */
    public boolean settled() {
        return pending == 0;
    }
}
