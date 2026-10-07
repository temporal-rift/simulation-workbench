package io.github.temporalrift.workbench.experiment.application.query;

import java.util.List;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.application.port.in.PreviewMatrixUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;

/** Previews the deterministic case matrix of a manifest without persisting anything. */
public class PreviewMatrixQueryHandler implements PreviewMatrixUseCase {

    @Override
    public List<MatrixPreviewService.CaseCoordinate> handle(JsonNode manifest) {
        ExperimentValidator.validate(manifest);
        return MatrixPreviewService.preview(manifest, ManifestDigest.sha256Hex(manifest));
    }
}
