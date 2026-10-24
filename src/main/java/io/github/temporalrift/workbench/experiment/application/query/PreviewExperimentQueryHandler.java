package io.github.temporalrift.workbench.experiment.application.query;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;

/**
 * Pages the matrix the freeze and the run enumerate, so a preview can never disagree with them. The manifest is
 * validated once, the total and breakdown are computed arithmetically, and only the requested window is built.
 */
public class PreviewExperimentQueryHandler implements PreviewExperimentUseCase {

    private final PolicyReferenceVerifier policyVerifier;
    private final VariantRules variantRules;

    public PreviewExperimentQueryHandler(PolicyReferenceVerifier policyVerifier, VariantRules variantRules) {
        this.policyVerifier = policyVerifier;
        this.variantRules = variantRules;
    }

    @Override
    public Result handle(Query query) {
        var view = ExperimentValidator.validate(query.manifest(), policyVerifier);
        var manifest = variantRules.freeze(query.manifest()).manifest();
        var digest = ManifestDigest.sha256Hex(manifest);
        var size = MatrixPreviewService.size(view);
        var window = MatrixPreviewService.page(view, digest, query.offset(), query.limit());
        var breakdown = size.breakdown().stream()
                .map(slice -> new Breakdown(slice.variantLabel(), slice.playerCount(), slice.caseCount()))
                .toList();
        return new Result(digest, size.total(), breakdown, window, query.limit(), query.offset());
    }
}
