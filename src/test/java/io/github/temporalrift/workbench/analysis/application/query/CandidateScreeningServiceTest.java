package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.CandidateScreening.Candidate;
import io.github.temporalrift.workbench.analysis.CandidateScreening.Games;
import io.github.temporalrift.workbench.analysis.CandidateScreening.Verdict;
import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.evaluation.EvaluationMode;
import io.github.temporalrift.workbench.analysis.domain.evaluation.RunEvaluator;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

class CandidateScreeningServiceTest {

    private static final String GREEDY = "faction-greedy-v1";
    private static final UUID FIRST_RUN = new UUID(1, 1);
    private static final UUID SECOND_RUN = new UUID(1, 2);

    private final Map<UUID, List<AnalyzedCase>> casesByRun = new HashMap<>();
    private final Map<UUID, CaseFacts> storedFacts = new HashMap<>();
    private final BalanceCriterion criterion = TestCriteria.v1();
    private final CandidateScreeningService service = new CandidateScreeningService(
            new RunSource() {
                @Override
                public Optional<UUID> experimentOf(UUID runId) {
                    return Optional.of(new UUID(9, 9));
                }

                @Override
                public List<AnalyzedCase> cases(UUID runId) {
                    return casesByRun.getOrDefault(runId, List.of());
                }

                @Override
                public Optional<GameRecord> countingGame(UUID runId, AnalyzedCase analyzedCase) {
                    throw new AssertionError("every fact is stored");
                }
            },
            new BalanceCriteria() {
                @Override
                public List<BalanceCriterion> all() {
                    return List.of(criterion);
                }

                @Override
                public Optional<BalanceCriterion> find(int version) {
                    return version == criterion.version() ? Optional.of(criterion) : Optional.empty();
                }
            },
            new CaseFactsProvider(null, new CaseFactsRepository() {
                @Override
                public Map<UUID, CaseFacts> find(Collection<UUID> caseIds, String analysisVersion) {
                    var found = new HashMap<>(storedFacts);
                    found.keySet().retainAll(caseIds);
                    return found;
                }

                @Override
                public void saveAll(Collection<CaseFacts> facts, String analysisVersion) {
                    throw new AssertionError("nothing is extracted");
                }
            }));

    @Test
    void theNewestCriterionVersionIsTheHighestServed() {
        assertThat(service.newestCriterionVersion()).isEqualTo(criterion.version());
    }

    @Test
    void aCandidateInOneRunIsEvaluatedAsRunEvaluationDoes() {
        var run = rotating("a", 0, 12, 21);
        play(FIRST_RUN, run);

        var screening = service.screen(
                criterion.version(), List.of(new Candidate("c-000", List.of(new Games(FIRST_RUN, "a")))));

        var expected = RunEvaluator.evaluate(
                FIRST_RUN, "a", criterion, EvaluationMode.SCREENING, AnalysisVersion.CURRENT, run.cases(), run.facts());
        var evaluation = screening.evaluations().get("c-000");
        assertThat(evaluation.verdict())
                .isEqualTo(Verdict.valueOf(expected.verdict().name()));
        assertThat(evaluation.score()).isEqualTo(expected.score());
    }

    @Test
    void aCandidateWhoseSeedsLieInTwoRunsIsEvaluatedOverAllOfThem() {
        var whole = rotating("a", 0, 12, 21);
        var early = new SyntheticRun("a", GREEDY, 22);
        var late = new SyntheticRun("a", GREEDY, 23);
        for (var seed = 1; seed <= 12; seed++) {
            (seed <= 4 ? early : late).win(String.valueOf(seed), SyntheticRun.EPW, seed % 3);
        }
        play(FIRST_RUN, early);
        play(SECOND_RUN, late);

        var screening = service.screen(
                criterion.version(),
                List.of(new Candidate("c-000", List.of(new Games(FIRST_RUN, "a"), new Games(SECOND_RUN, "a")))));

        var expected = RunEvaluator.evaluate(
                FIRST_RUN,
                "a",
                criterion,
                EvaluationMode.SCREENING,
                AnalysisVersion.CURRENT,
                whole.cases(),
                whole.facts());
        assertThat(screening.evaluations().get("c-000").score()).isEqualTo(expected.score());
    }

    @Test
    void aRejectedCandidateTakesNoPartInDominance() {
        play(FIRST_RUN, rotating("good", 0, 12, 24));
        play(SECOND_RUN, erasersAlways("bad", 12, 25));

        var screening = service.screen(
                criterion.version(),
                List.of(
                        new Candidate("c-000", List.of(new Games(FIRST_RUN, "good"))),
                        new Candidate("c-001", List.of(new Games(SECOND_RUN, "bad")))));

        assertThat(screening.evaluations().get("c-001").verdict()).isEqualTo(Verdict.REJECTED);
        assertThat(screening.evaluations().get("c-001").failingGateLineIds()).isNotEmpty();
        assertThat(screening.dominators()).isEmpty();
    }

    @Test
    void aCandidateWithoutGamesIsRefused() {
        var criterionVersion = criterion.version();
        var noGames = List.of(new Candidate("c-000", List.of()));
        var gamesOfNoCase = List.of(new Candidate("c-000", List.of(new Games(FIRST_RUN, "none"))));

        assertThatThrownBy(() -> service.screen(criterionVersion, noGames))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.screen(criterionVersion, gamesOfNoCase))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anUnknownCriterionIsRefused() {
        assertThatThrownBy(() -> service.screen(99, List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    private void play(UUID runId, SyntheticRun run) {
        casesByRun.computeIfAbsent(runId, ignored -> new ArrayList<>()).addAll(run.cases());
        storedFacts.putAll(run.facts());
    }

    private static SyntheticRun rotating(String variant, int offset, int seeds, long idSpace) {
        var run = new SyntheticRun(variant, GREEDY, idSpace);
        for (var seed = 1; seed <= seeds; seed++) {
            run.win(String.valueOf(seed), SyntheticRun.EPW, (seed + offset) % 3);
        }
        return run;
    }

    private static SyntheticRun erasersAlways(String variant, int seeds, long idSpace) {
        var run = new SyntheticRun(variant, GREEDY, idSpace);
        for (var seed = 1; seed <= seeds; seed++) {
            run.win(String.valueOf(seed), SyntheticRun.EPW, 0);
        }
        return run;
    }
}
