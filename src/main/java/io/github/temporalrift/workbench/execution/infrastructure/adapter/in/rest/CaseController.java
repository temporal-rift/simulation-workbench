package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ListCasesUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.CasesApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseList;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ModelCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Replay;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReplayPerspective;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Reproduction;

@RestController
class CaseController implements CasesApi {

    private static final int DEFAULT_LIMIT = 100;
    private static final String OBSERVE_AUTHORITY = "SCOPE_simulation:observe";

    private final GetCaseUseCase getCase;
    private final GetCaseReplayUseCase getCaseReplay;
    private final ReproduceCaseUseCase reproduceCase;
    private final ListCasesUseCase listCases;

    CaseController(
            GetCaseUseCase getCase,
            GetCaseReplayUseCase getCaseReplay,
            ReproduceCaseUseCase reproduceCase,
            ListCasesUseCase listCases) {
        this.getCase = getCase;
        this.getCaseReplay = getCaseReplay;
        this.reproduceCase = reproduceCase;
        this.listCases = listCases;
    }

    @Override
    public ResponseEntity<CaseList> listCases(
            UUID runId, CaseState state, String variantLabel, Integer limit, Integer offset) {
        var page = listCases.handle(
                new ListCasesUseCase.Query(runId, RunApiMapper.toDomain(state), variantLabel, limit, offset));
        return ResponseEntity.ok(new CaseList(
                page.items().stream().map(RunApiMapper::toSummary).toList(), Math.toIntExact(page.total())));
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
        if (perspective == ReplayPerspective.OBSERVER) {
            requireObserveScope();
        }
        var page = getCaseReplay.handle(new GetCaseReplayUseCase.Query(
                runId,
                caseId,
                GetCaseReplayUseCase.Perspective.valueOf(perspective.name()),
                seatIndex,
                afterStep,
                limit == null ? DEFAULT_LIMIT : limit));
        return ResponseEntity.ok(ReplayApiMapper.toApi(page));
    }

    @Override
    public ResponseEntity<Reproduction> reproduceCase(UUID runId, UUID caseId, UUID idempotencyKey) {
        var reproduction = reproduceCase.handle(new ReproduceCaseUseCase.Command(runId, caseId, idempotencyKey));
        return ResponseEntity.accepted().body(ReplayApiMapper.toApi(reproduction));
    }

    /** Observer evidence is a separate entitlement from reading cases. */
    private static void requireObserveScope() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        var entitled = authentication != null
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> OBSERVE_AUTHORITY.equals(authority.getAuthority()));
        if (!entitled) {
            throw new AccessDeniedException("An OBSERVER replay requires the simulation:observe scope");
        }
    }
}
