package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.ScoringModelsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RescoreRequest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Rescoring;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelRequest;

@RestController
class ScoringModelController implements ScoringModelsApi {

    private final EvaluateScoringModelUseCase evaluateScoringModel;

    ScoringModelController(EvaluateScoringModelUseCase evaluateScoringModel) {
        this.evaluateScoringModel = evaluateScoringModel;
    }

    @Override
    public ResponseEntity<ScoringModelEvaluation> evaluateScoringModel(ScoringModelRequest request) {
        var result = evaluateScoringModel.handle(ScoringModelApiMapper.query(request));
        return ResponseEntity.ok(ScoringModelApiMapper.evaluation(result));
    }

    /** Re-scoring a recorded run belongs to a separate change; until it lands the operation is not served. */
    @Override
    public ResponseEntity<Rescoring> rescoreRun(UUID runId, RescoreRequest request) {
        return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED).build();
    }
}
