package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.List;

/** Whether the run is valid evidence; every applicable reason is listed, not only the first. */
public record Validity(List<Reason> reasons) {

    /** One reason with the numbers that caused it. */
    public record Reason(ValidityCode code, String detail) {}

    public Validity {
        reasons = List.copyOf(reasons);
    }

    public boolean valid() {
        return reasons.isEmpty();
    }
}
