package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.CasesApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ModelCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Replay;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReplayPerspective;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Reproduction;

@RestController
class CaseController implements CasesApi {

    private final GetCaseUseCase getCase;

    CaseController(GetCaseUseCase getCase) {
        this.getCase = getCase;
    }

    @Override
    public ResponseEntity<ModelCase> getCase(UUID runId, UUID caseId) {
        return ResponseEntity.ok(RunApiMapper.toApi(getCase.handle(runId, caseId)));
    }

    @Override
    public ResponseEntity<Replay> getCaseReplay(
            UUID runId,
            UUID caseId,
            ReplayPerspective perspective,
            Integer seatIndex,
            Integer afterStep,
            Integer limit) {
        throw new OperationNotAvailableException("getCaseReplay");
    }

    @Override
    public ResponseEntity<Reproduction> reproduceCase(UUID runId, UUID caseId, UUID idempotencyKey) {
        throw new OperationNotAvailableException("reproduceCase");
    }
}
