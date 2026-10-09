package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.execution.application.port.in.CancelRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.RunsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Run;

@RestController
class RunController implements RunsApi {

    private final StartRunUseCase startRun;
    private final GetRunUseCase getRun;
    private final CancelRunUseCase cancelRun;
    private final ResumeRunUseCase resumeRun;

    RunController(
            StartRunUseCase startRun, GetRunUseCase getRun, CancelRunUseCase cancelRun, ResumeRunUseCase resumeRun) {
        this.startRun = startRun;
        this.getRun = getRun;
        this.cancelRun = cancelRun;
        this.resumeRun = resumeRun;
    }

    @Override
    public ResponseEntity<Run> startRun(UUID experimentId, UUID idempotencyKey, Object body) {
        var view = startRun.handle(new StartRunUseCase.Command(experimentId, idempotencyKey));
        return ResponseEntity.accepted().body(RunApiMapper.toApi(view));
    }

    @Override
    public ResponseEntity<Run> getRun(UUID runId) {
        return ResponseEntity.ok(RunApiMapper.toApi(getRun.handle(runId)));
    }

    @Override
    public ResponseEntity<Run> cancelRun(UUID runId, UUID idempotencyKey) {
        var result = cancelRun.handle(new CancelRunUseCase.Command(runId, idempotencyKey));
        var body = RunApiMapper.toApi(result.run());
        return result.accepted() ? ResponseEntity.accepted().body(body) : ResponseEntity.ok(body);
    }

    @Override
    public ResponseEntity<Run> resumeRun(UUID runId, UUID idempotencyKey) {
        var view = resumeRun.handle(new ResumeRunUseCase.Command(runId, idempotencyKey));
        return ResponseEntity.accepted().body(RunApiMapper.toApi(view));
    }
}
