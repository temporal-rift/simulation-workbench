package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunEvaluationUseCase;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.EvaluationsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RunEvaluation;

@RestController
class EvaluationController implements EvaluationsApi {

    private final GetRunEvaluationUseCase getRunEvaluation;

    EvaluationController(GetRunEvaluationUseCase getRunEvaluation) {
        this.getRunEvaluation = getRunEvaluation;
    }

    @Override
    public ResponseEntity<RunEvaluation> getRunEvaluation(
            UUID runId, String variantLabel, Integer criterionVersion, String mode) {
        var evaluation = getRunEvaluation.handle(
                new GetRunEvaluationUseCase.Query(runId, variantLabel, criterionVersion, parse(mode)));
        return ResponseEntity.ok(EvaluationApiMapper.evaluation(evaluation));
    }

    private static EvaluationMode parse(String mode) {
        try {
            return EvaluationMode.valueOf(mode);
        } catch (IllegalArgumentException _) {
            throw new InvalidEvaluationRequestException("mode must be SCREENING or CONFIRMATION");
        }
    }
}
