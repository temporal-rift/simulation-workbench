package io.github.temporalrift.workbench.analysis.domain.criterion;

/** A gate rejects a configuration when it fails; a scored line contributes to the aggregate score. */
public enum CriterionRole {
    GATE,
    SCORED
}
