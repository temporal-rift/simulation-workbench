package io.github.temporalrift.workbench.experiment.application.port.in;

import java.util.List;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;

/** Enumerates the deterministic case matrix of a frozen manifest without persisting anything. */
public interface PreviewMatrixUseCase {

    List<MatrixPreviewService.CaseCoordinate> handle(JsonNode manifest);
}
