package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.ScoringModelsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RescoreRequest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Rescoring;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelRequest;

@RestController
class ScoringModelController implements ScoringModelsApi {

    private final EvaluateScoringModelUseCase evaluateScoringModel;
    private final RescoreRunUseCase rescoreRun;

    ScoringModelController(EvaluateScoringModelUseCase evaluateScoringModel, RescoreRunUseCase rescoreRun) {
        this.evaluateScoringModel = evaluateScoringModel;
        this.rescoreRun = rescoreRun;
    }

    @Override
    public ResponseEntity<ScoringModelEvaluation> evaluateScoringModel(ScoringModelRequest request) {
        var result = evaluateScoringModel.handle(ScoringModelApiMapper.query(request));
        return ResponseEntity.ok(ScoringModelApiMapper.evaluation(result));
    }

    @Override
    public ResponseEntity<Rescoring> rescoreRun(UUID runId, RescoreRequest request) {
        var result = rescoreRun.handle(ScoringModelApiMapper.rescoreQuery(runId, request));
        return ResponseEntity.ok(ScoringModelApiMapper.rescoring(result));
    }
}
