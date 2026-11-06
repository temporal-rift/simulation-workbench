package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunEvaluationUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationVerdict;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluation;
import io.github.temporalrift.workbench.analysis.domain.evaluation.Validity;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;

class RunConfirmationServiceTest {

    private static final UUID RUN = new UUID(1, 1);

    private final BalanceCriterion criterion = TestCriteria.v1();
    private final GetRunEvaluationUseCase evaluations = mock(GetRunEvaluationUseCase.class);
    private final RunConfirmationService service = new RunConfirmationService(evaluations, new BalanceCriteria() {
        @Override
        public List<BalanceCriterion> all() {
            return List.of(criterion);
        }

        @Override
        public Optional<BalanceCriterion> find(int version) {
            return version == criterion.version() ? Optional.of(criterion) : Optional.empty();
        }
    });

    @Test
    void theMinimumSeedsAreTheCriterionsConfirmationMinimum() {
        assertThat(service.minimumSeeds(criterion.version()))
                .isEqualTo(criterion.evidence().confirmationMinimumSeeds());
    }

    @Test
    void thePoliciesAreTheOnesTheCriterionNamesAsIdAndMajorVersion() {
        assertThat(service.policies(criterion.version()))
                .containsExactlyInAnyOrder(
                        new io.github.temporalrift.workbench.analysis.RunConfirmation.PolicyRef("faction-greedy", "1"),
                        new io.github.temporalrift.workbench.analysis.RunConfirmation.PolicyRef("random", "1"));
    }

    @Test
    void anUnknownCriterionIsRefused() {
        assertThatThrownBy(() -> service.minimumSeeds(99)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRunVariantIsEvaluatedInConfirmationModeExactlyAsTheEvaluationEndpointDoes() {
        when(evaluations.handle(new GetRunEvaluationUseCase.Query(
                        RUN, "c-0001", criterion.version(), EvaluationMode.CONFIRMATION)))
                .thenReturn(evaluation(EvaluationVerdict.CONFIRMED, 2.5));

        var result = service.confirm(criterion.version(), RUN, "c-0001");

        assertThat(result.verdict()).isEqualTo("CONFIRMED");
        assertThat(result.score()).isEqualTo(2.5);
        verify(evaluations)
                .handle(new GetRunEvaluationUseCase.Query(
                        RUN, "c-0001", criterion.version(), EvaluationMode.CONFIRMATION));
    }

    @Test
    void anInvalidEvaluationHasNoScore() {
        when(evaluations.handle(new GetRunEvaluationUseCase.Query(
                        RUN, "baseline", criterion.version(), EvaluationMode.CONFIRMATION)))
                .thenReturn(evaluation(EvaluationVerdict.INVALID, null));

        var result = service.confirm(criterion.version(), RUN, "baseline");

        assertThat(result.verdict()).isEqualTo("INVALID");
        assertThat(result.score()).isNull();
    }

    @Test
    void anUnknownRunVariantIsRefusedAsAnIllegalArgument() {
        when(evaluations.handle(new GetRunEvaluationUseCase.Query(
                        RUN, "gone", criterion.version(), EvaluationMode.CONFIRMATION)))
                .thenThrow(new AnalysisResourceNotFoundException("Variant", "gone"));

        assertThatThrownBy(() -> service.confirm(criterion.version(), RUN, "gone"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("gone");
    }

    private RunEvaluation evaluation(EvaluationVerdict verdict, Double score) {
        return new RunEvaluation(
                RUN,
                "c-0001",
                criterion.version(),
                "digest",
                new AnalysisVersion(criterion.analysisVersion(), AnalysisVersion.CURRENT.seed()),
                EvaluationMode.CONFIRMATION,
                new Validity(List.of()),
                verdict,
                score,
                null,
                List.of());
    }
}
