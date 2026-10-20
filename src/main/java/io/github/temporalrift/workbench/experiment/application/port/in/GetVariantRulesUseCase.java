package io.github.temporalrift.workbench.experiment.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.experiment.EffectiveRules;

/** Reads the effective rules a frozen experiment's variant plays under. */
public interface GetVariantRulesUseCase {

    EffectiveRules handle(UUID experimentId, String label);
}
