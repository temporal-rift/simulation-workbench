package io.github.temporalrift.workbench.experiment.application.query;

import java.util.List;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewMatrixUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;

/** Previews the deterministic case matrix of a manifest without persisting anything. */
public class PreviewMatrixQueryHandler implements PreviewMatrixUseCase {

    private final PolicyReferenceVerifier policyVerifier;

    public PreviewMatrixQueryHandler(PolicyReferenceVerifier policyVerifier) {
        this.policyVerifier = policyVerifier;
    }

    @Override
    public List<MatrixPreviewService.CaseCoordinate> handle(JsonNode manifest) {
        ExperimentValidator.validate(manifest, policyVerifier);
        return MatrixPreviewService.preview(manifest, ManifestDigest.sha256Hex(manifest));
    }
}
