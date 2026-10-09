package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

class CaseFactsProviderTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final List<Faction> SEATS = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);

    private final Map<String, Map<UUID, CaseFacts>> stored = new HashMap<>();
    private final AtomicInteger records = new AtomicInteger();
    private final CaseFactsProvider provider = new CaseFactsProvider(
            new RunSource() {
                @Override
                public Optional<UUID> experimentOf(UUID runId) {
                    return Optional.empty();
                }

                @Override
                public List<AnalyzedCase> cases(UUID runId) {
                    return List.of();
                }

                @Override
                public Optional<GameRecord> countingGame(UUID runId, AnalyzedCase analyzedCase) {
                    records.incrementAndGet();
                    return Optional.of(new GameRecord(List.of(), List.of(), List.of()));
                }
            },
            new CaseFactsRepository() {
                @Override
                public Map<UUID, CaseFacts> find(Collection<UUID> caseIds, String analysisVersion) {
                    var found = new HashMap<>(stored.getOrDefault(analysisVersion, Map.of()));
                    found.keySet().retainAll(caseIds);
                    return found;
                }

                @Override
                public void save(CaseFacts facts, String analysisVersion) {
                    stored.computeIfAbsent(analysisVersion, version -> new HashMap<>())
                            .putIfAbsent(facts.caseId(), facts);
                }
            });

    @Test
    void factsAreStoredUnderTheRequestedVersionAndExtractedOnce() {
        var cases = List.of(succeeded(new UUID(1, 1)));

        var first = provider.factsOf(RUN, cases, AnalysisVersion.CURRENT);
        var again = provider.factsOf(RUN, cases, AnalysisVersion.CURRENT);

        assertThat(stored).containsOnlyKeys(AnalysisVersion.CURRENT.version());
        assertThat(again).isEqualTo(first);
        assertThat(records).hasValue(1);
    }

    @Test
    void aVersionThisWorkbenchDoesNotImplementFailsInsteadOfBorrowingAnotherVersionsFacts() {
        var cases = List.of(succeeded(new UUID(1, 2)));

        assertThatIllegalStateException()
                .isThrownBy(() -> provider.factsOf(RUN, cases, new AnalysisVersion("2", 1L)))
                .withMessageContaining("Analysis version 2");
        assertThat(stored).isEmpty();
    }

    private static AnalyzedCase succeeded(UUID caseId) {
        return new AnalyzedCase(
                caseId,
                "base",
                "1",
                3,
                "random",
                "1.0.0",
                SEATS,
                CaseStatus.SUCCEEDED,
                new CaseOutcome(EndReason.WIN_CONDITION_MET, Set.of(0), List.of(20, 15, 10), 3, 9, 30));
    }
}
