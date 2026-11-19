package io.github.temporalrift.workbench.analysis.application.query;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.RunConfirmation;
import io.github.temporalrift.workbench.analysis.application.port.in.GetRunEvaluationUseCase;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionPhase;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;
import io.github.temporalrift.workbench.policy.PolicyCatalog;

/** Confirms a run variant through the same evaluation the evaluation endpoint serves. */
public class RunConfirmationService implements RunConfirmation {

    private final GetRunEvaluationUseCase evaluations;
    private final BalanceCriteria criteria;
    private final PolicyCatalog policyCatalog;

    public RunConfirmationService(
            GetRunEvaluationUseCase evaluations, BalanceCriteria criteria, PolicyCatalog policyCatalog) {
        this.evaluations = evaluations;
        this.criteria = criteria;
        this.policyCatalog = policyCatalog;
    }

    @Override
    public int minimumSeeds(int criterionVersion) {
        return criterion(criterionVersion).evidence().confirmationMinimumSeeds();
    }

    @Override
    public List<PolicyRef> policies(int criterionVersion) {
        return criterion(criterionVersion).policies().stream()
                .map(this::resolve)
                .toList();
    }

    @Override
    public List<Table> screeningTables(int criterionVersion) {
        return tables(criterionVersion, CriterionPhase.SCREENING);
    }

    @Override
    public List<Table> confirmationTables(int criterionVersion) {
        return tables(criterionVersion, CriterionPhase.CONFIRMATION);
    }

    private List<Table> tables(int criterionVersion, CriterionPhase phase) {
        return criterion(criterionVersion).tables(phase).stream()
                .map(table -> new Table(
                        table.id(), table.seats().stream().map(this::resolve).toList()))
                .toList();
    }

    private BalanceCriterion criterion(int criterionVersion) {
        return criteria.find(criterionVersion)
                .orElseThrow(() -> new IllegalArgumentException("Unknown balance criterion " + criterionVersion));
    }

    /** A criterion names a policy by id; the one bundle of that id gives the version. */
    private PolicyRef resolve(String policyId) {
        var bundle = policyCatalog.list().stream()
                .filter(descriptor -> descriptor.id().equals(policyId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown policy " + policyId));
        var version = bundle.version();
        var dot = version.indexOf('.');
        return new PolicyRef(policyId, dot < 0 ? version : version.substring(0, dot));
    }

    @Override
    public Result confirm(int criterionVersion, UUID runId, String variantLabel) {
        try {
            var evaluation = evaluations.handle(new GetRunEvaluationUseCase.Query(
                    runId, variantLabel, criterionVersion, EvaluationMode.CONFIRMATION));
            return new Result(evaluation.verdict().name(), evaluation.score());
        } catch (io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException e) {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }
}
