package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.analysis.application.port.in.GetBalanceCriterionUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.ListBalanceCriteriaUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.CriteriaApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.BalanceCriterion;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.BalanceCriterionCatalog;

@RestController
class CriteriaController implements CriteriaApi {

    private final ListBalanceCriteriaUseCase listCriteria;
    private final GetBalanceCriterionUseCase getCriterion;

    CriteriaController(ListBalanceCriteriaUseCase listCriteria, GetBalanceCriterionUseCase getCriterion) {
        this.listCriteria = listCriteria;
        this.getCriterion = getCriterion;
    }

    @Override
    public ResponseEntity<BalanceCriterionCatalog> listBalanceCriteria() {
        return ResponseEntity.ok(EvaluationApiMapper.catalog(listCriteria.handle()));
    }

    @Override
    public ResponseEntity<BalanceCriterion> getBalanceCriterion(Integer criterionVersion) {
        return ResponseEntity.ok(EvaluationApiMapper.criterion(getCriterion.handle(criterionVersion)));
    }
}
