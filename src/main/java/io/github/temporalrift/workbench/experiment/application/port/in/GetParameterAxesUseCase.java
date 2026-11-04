package io.github.temporalrift.workbench.experiment.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;

/** The parameters a baseline's overrides may set, with the values the baseline carries. */
public interface GetParameterAxesUseCase {

    List<VariantRules.Axis> handle(String rulesArtifactDigest, int parameterSpaceVersion);
}
