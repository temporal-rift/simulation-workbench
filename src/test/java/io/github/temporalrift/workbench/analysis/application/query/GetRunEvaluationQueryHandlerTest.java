package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.application.port.in.GetRunEvaluationUseCase;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.UnknownCriterionTablesException;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.Faction;

/** A run whose tables the criterion does not list is refused instead of evaluated into lines with no data. */
class GetRunEvaluationQueryHandlerTest {

    private static final UUID RUN = new UUID(7, 7);

    private final BalanceCriterion criterion = TestCriteria.current();
    private final RunSource runs = mock(RunSource.class);
    private final BalanceCriteria criteria = new BalanceCriteria() {
        @Override
        public List<BalanceCriterion> all() {
            return List.of(criterion);
        }

        @Override
        public Optional<BalanceCriterion> find(int version) {
            return version == criterion.version() ? Optional.of(criterion) : Optional.empty();
        }
    };
    private final GetRunEvaluationUseCase handler =
            new GetRunEvaluationQueryHandler(runs, criteria, mock(CaseFactsProvider.class));

    @Test
    void aTableTheCriterionDoesNotListIsRefusedNamingIt() {
        when(runs.experimentOf(RUN)).thenReturn(Optional.of(new UUID(8, 8)));
        when(runs.cases(RUN)).thenReturn(List.of(played("faction-greedy-v4")));

        var query = new GetRunEvaluationUseCase.Query(RUN, "baseline", criterion.version(), EvaluationMode.SCREENING);

        assertThatThrownBy(() -> handler.handle(query))
                .isInstanceOf(UnknownCriterionTablesException.class)
                .hasMessageContaining("faction-greedy-v4")
                .hasMessageContaining("faction-greedy, random");
    }

    @Test
    void aListedTableIsNotRefusedByThePreflight() {
        when(runs.experimentOf(RUN)).thenReturn(Optional.of(new UUID(8, 8)));
        when(runs.cases(RUN)).thenReturn(List.of(played("faction-greedy")));

        var query = new GetRunEvaluationUseCase.Query(RUN, "baseline", criterion.version(), EvaluationMode.SCREENING);

        try {
            handler.handle(query);
        } catch (RuntimeException e) {
            assertThat(e).isNotInstanceOf(UnknownCriterionTablesException.class);
        }
        assertThat(criterion.tables()).anyMatch(table -> table.id().equals("faction-greedy"));
    }

    private static AnalyzedCase played(String tableId) {
        return new AnalyzedCase(
                new UUID(1, 1),
                "baseline",
                tableId,
                "1",
                3,
                List.of(
                        new SeatPolicy("faction-greedy", "1.0.0"),
                        new SeatPolicy("faction-greedy", "1.0.0"),
                        new SeatPolicy("faction-greedy", "1.0.0")),
                List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS),
                CaseState.FAILED,
                null,
                null);
    }
}
