package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.comparison.ExperimentDefinition;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.ExperimentDefinitions;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

class GetRunReportQueryHandlerTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final List<Faction> SEATS = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);

    private final List<AnalyzedCase> cases = new ArrayList<>();
    private final AtomicInteger extractions = new AtomicInteger();
    private final RunSource source = new RunSource() {
        @Override
        public Optional<UUID> experimentOf(UUID runId) {
            return Optional.of(new UUID(9, 9));
        }

        @Override
        public List<AnalyzedCase> cases(UUID runId) {
            return List.copyOf(cases);
        }

        @Override
        public Optional<GameRecord> countingGame(UUID runId, AnalyzedCase analyzedCase) {
            extractions.incrementAndGet();
            return Optional.of(new GameRecord(List.of(), List.of(), List.of()));
        }
    };
    private final GetRunReportQueryHandler handler = new GetRunReportQueryHandler(
            source,
            experimentId -> Optional.of(new ExperimentDefinitions.FrozenExperiment(
                    "a".repeat(64), new ExperimentDefinition(new java.util.TreeMap<>(), Map.of()))),
            new CaseFactsProvider(source, new CaseFactsRepository() {
                private final Map<UUID, CaseFacts> stored = new java.util.HashMap<>();

                @Override
                public Map<UUID, CaseFacts> find(Collection<UUID> caseIds, String analysisVersion) {
                    var found = new java.util.HashMap<>(stored);
                    found.keySet().retainAll(caseIds);
                    return found;
                }

                @Override
                public void saveAll(Collection<CaseFacts> facts, String analysisVersion) {
                    facts.forEach(caseFacts -> stored.put(caseFacts.caseId(), caseFacts));
                }
            }));

    @Test
    void anUnchangedRunKeepsItsReportWithoutExtractingOrComputingAgain() {
        cases.add(succeeded(1));

        var first = handler.handle(RUN);
        var second = handler.handle(RUN);

        assertThat(second).isSameAs(first);
        assertThat(extractions).hasValue(1);
    }

    @Test
    void aCaseThatSucceedsReplacesTheCachedReportOnceAndLaterReadsReuseIt() {
        cases.add(succeeded(1));
        cases.add(pending(2));
        var before = handler.handle(RUN);

        cases.set(1, succeeded(2));
        var after = handler.handle(RUN);
        var again = handler.handle(RUN);

        assertThat(after).isNotSameAs(before);
        assertThat(after.caseCounts().succeeded()).isEqualTo(2);
        assertThat(again).isSameAs(after);
        assertThat(extractions).hasValue(2);
    }

    private static AnalyzedCase succeeded(int index) {
        return new AnalyzedCase(
                new UUID(1, index),
                "base",
                String.valueOf(index),
                3,
                "random",
                "1.0.0",
                SEATS,
                CaseStatus.SUCCEEDED,
                new CaseOutcome(EndReason.WIN_CONDITION_MET, Set.of(0), List.of(20, 15, 10), 3, 9, 30));
    }

    private static AnalyzedCase pending(int index) {
        return new AnalyzedCase(
                new UUID(1, index),
                "base",
                String.valueOf(index),
                3,
                "random",
                "1.0.0",
                SEATS,
                CaseStatus.PENDING,
                null);
    }
}
