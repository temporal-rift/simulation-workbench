package io.github.temporalrift.workbench.experiment.domain;

/** Why a variant's baseline or overrides were refused, as the published contract names it. */
public enum VariantRulesReason {
    UNKNOWN_PARAMETER_SPACE,
    UNKNOWN_BASELINE,
    UNKNOWN_PARAMETER,
    OUT_OF_RANGE,
    OFF_STEP,
    CONSTRAINT_VIOLATED
}
