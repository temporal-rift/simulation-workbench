package io.github.temporalrift.workbench.execution.domain.run;

/** Logical-case totals of a run; each logical case is counted once whatever its attempts. */
public record CaseCounts(int requested, int pending, int running, int succeeded, int failed, int cancelled) {

    /** Cases that have not reached a final state. */
    public int unfinished() {
        return pending + running;
    }
}
