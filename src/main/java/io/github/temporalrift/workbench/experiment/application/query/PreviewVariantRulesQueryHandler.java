package io.github.temporalrift.workbench.experiment.application.query;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;

public class PreviewVariantRulesQueryHandler implements PreviewVariantRulesUseCase {

    private final VariantRules variantRules;

    public PreviewVariantRulesQueryHandler(VariantRules variantRules) {
        this.variantRules = variantRules;
    }

    @Override
    public Result handle(Query query) {
        var preview =
                variantRules.preview(query.rulesArtifactDigest(), query.parameterSpaceVersion(), query.overrides());
        return new Result(preview.rules(), preview.digest(), preview.overrides());
    }
}
