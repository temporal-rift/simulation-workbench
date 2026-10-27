package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.fidelity.application.port.in.GetFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityCasesUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityChecksUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.FidelityApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCasePage;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCaseResult;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheck;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheckPage;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheckRequest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityCheckState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FidelityVariantLabel;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SimulatorFidelity;

@RestController
class FidelityController implements FidelityApi {

    private final StartFidelityCheckUseCase startCheck;
    private final GetFidelityCheckUseCase getCheck;
    private final ListFidelityChecksUseCase listChecks;
    private final ListFidelityCasesUseCase listCases;
    private final GetSimulatorFidelityUseCase getSimulatorFidelity;

    FidelityController(
            StartFidelityCheckUseCase startCheck,
            GetFidelityCheckUseCase getCheck,
            ListFidelityChecksUseCase listChecks,
            ListFidelityCasesUseCase listCases,
            GetSimulatorFidelityUseCase getSimulatorFidelity) {
        this.startCheck = startCheck;
        this.getCheck = getCheck;
        this.listChecks = listChecks;
        this.listCases = listCases;
        this.getSimulatorFidelity = getSimulatorFidelity;
    }

    @Override
    public ResponseEntity<FidelityCheck> startFidelityCheck(UUID idempotencyKey, FidelityCheckRequest request) {
        var view = startCheck.handle(new StartFidelityCheckUseCase.Command(idempotencyKey, request.getSeed()));
        return ResponseEntity.accepted().body(FidelityApiMapper.toApi(view));
    }

    @Override
    public ResponseEntity<FidelityCheckPage> listFidelityChecks(
            FidelityCheckState state, Integer limit, Integer offset) {
        var page =
                listChecks.handle(new ListFidelityChecksUseCase.Query(FidelityApiMapper.domain(state), limit, offset));
        return ResponseEntity.ok(new FidelityCheckPage(
                page.items().stream().map(FidelityApiMapper::toApi).toList(), Math.toIntExact(page.total())));
    }

    @Override
    public ResponseEntity<FidelityCheck> getFidelityCheck(UUID fidelityCheckId) {
        return ResponseEntity.ok(FidelityApiMapper.toApi(getCheck.handle(fidelityCheckId)));
    }

    @Override
    public ResponseEntity<FidelityCasePage> listFidelityCases(
            UUID fidelityCheckId,
            FidelityVariantLabel variantLabel,
            FidelityCaseResult result,
            Integer limit,
            Integer offset) {
        var page = listCases.handle(new ListFidelityCasesUseCase.Query(
                fidelityCheckId,
                FidelityApiMapper.domain(variantLabel),
                FidelityApiMapper.domain(result),
                limit,
                offset));
        return ResponseEntity.ok(new FidelityCasePage(
                page.items().stream().map(FidelityApiMapper::toApi).toList(), Math.toIntExact(page.total())));
    }

    @Override
    public ResponseEntity<SimulatorFidelity> getSimulatorFidelity() {
        return ResponseEntity.ok(FidelityApiMapper.toApi(getSimulatorFidelity.handle()));
    }
}
