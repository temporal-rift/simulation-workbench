package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.analysis.application.port.in.CreateComparisonUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.GetComparisonUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.ListComparisonsUseCase;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonSide;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.ComparisonsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Comparison;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonList;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ComparisonRequest;

@RestController
class ComparisonController implements ComparisonsApi {

    private final CreateComparisonUseCase createComparison;
    private final GetComparisonUseCase getComparison;
    private final ListComparisonsUseCase listComparisons;

    ComparisonController(
            CreateComparisonUseCase createComparison,
            GetComparisonUseCase getComparison,
            ListComparisonsUseCase listComparisons) {
        this.createComparison = createComparison;
        this.getComparison = getComparison;
        this.listComparisons = listComparisons;
    }

    @Override
    public ResponseEntity<Comparison> createComparison(UUID idempotencyKey, ComparisonRequest request) {
        var result = createComparison.handle(new CreateComparisonUseCase.Command(
                idempotencyKey,
                new ComparisonSide(
                        request.getBaseline().getRunId(), request.getBaseline().getVariantLabel()),
                new ComparisonSide(
                        request.getCandidate().getRunId(),
                        request.getCandidate().getVariantLabel())));
        return ResponseEntity.status(HttpStatus.CREATED).body(AnalysisApiMapper.comparison(result));
    }

    @Override
    public ResponseEntity<ComparisonList> listComparisons(UUID runId, Integer limit, Integer offset) {
        var page = listComparisons.handle(new ListComparisonsUseCase.Query(runId, limit, offset));
        return ResponseEntity.ok(new ComparisonList(
                page.items().stream().map(AnalysisApiMapper::summary).toList(), Math.toIntExact(page.total())));
    }

    @Override
    public ResponseEntity<Comparison> getComparison(UUID comparisonId) {
        return ResponseEntity.ok(AnalysisApiMapper.comparison(getComparison.handle(comparisonId)));
    }
}
