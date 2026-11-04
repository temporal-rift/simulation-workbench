package io.github.temporalrift.workbench.experiment.application.query;

import java.util.List;

import io.github.temporalrift.workbench.experiment.application.port.in.GetParameterAxesUseCase;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;

public class GetParameterAxesQueryHandler implements GetParameterAxesUseCase {

    private final VariantRules variantRules;

    public GetParameterAxesQueryHandler(VariantRules variantRules) {
        this.variantRules = variantRules;
    }

    @Override
    public List<VariantRules.Axis> handle(String rulesArtifactDigest, int parameterSpaceVersion) {
        return variantRules.axes(rulesArtifactDigest, parameterSpaceVersion);
    }
}
