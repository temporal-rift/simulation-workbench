package io.github.temporalrift.workbench.fidelity.application;

import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCounts;

/** A check with the counts of its cases. */
public record FidelityCheckView(FidelityCheck check, FidelityCounts counts) {}
