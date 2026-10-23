package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.ArrayList;
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
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

class CaseFactsProviderTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final List<Faction> SEATS = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);

    private final Map<String, Map<UUID, CaseFacts>> stored = new HashMap<>();
    private final AtomicInteger records = new AtomicInteger();
    private final AtomicInteger saves = new AtomicInteger();
    private final List<Integer> lookupSizes = new ArrayList<>();
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
                    lookupSizes.add(caseIds.size());
                    var found = new HashMap<>(stored.getOrDefault(analysisVersion, Map.of()));
                    found.keySet().retainAll(caseIds);
                    return found;
                }

                @Override
                public void saveAll(Collection<CaseFacts> facts, String analysisVersion) {
                    saves.incrementAndGet();
                    facts.forEach(caseFacts -> stored.computeIfAbsent(analysisVersion, version -> new HashMap<>())
                            .putIfAbsent(caseFacts.caseId(), caseFacts));
                }
            });

    @Test
    void factsAreExtractedOnceAndSharedByEverySupportedVersion() {
        var cases = List.of(succeeded(new UUID(1, 1)));

        var first = provider.factsOf(RUN, cases, AnalysisVersion.CURRENT);
        var again = provider.factsOf(RUN, cases, new AnalysisVersion("1", 1L));

        assertThat(stored).containsOnlyKeys(AnalysisVersion.CURRENT.factsVersion());
        assertThat(again).isEqualTo(first);
        assertThat(records).hasValue(1);
    }

    @Test
    void aLargeBackfillLooksFactsUpInChunksAndStoresThemInOneBatch() {
        var cases = new ArrayList<AnalyzedCase>();
        for (var index = 0; index < 2500; index++) {
            cases.add(succeeded(new UUID(2, index)));
        }

        var facts = provider.factsOf(RUN, cases, AnalysisVersion.CURRENT);

        assertThat(facts).hasSize(2500);
        assertThat(lookupSizes).containsExactly(1000, 1000, 500);
        assertThat(saves).hasValue(1);
    }

    @Test
    void aVersionThisWorkbenchDoesNotImplementFailsInsteadOfBorrowingAnotherVersionsFacts() {
        var cases = List.of(succeeded(new UUID(1, 2)));

        assertThatIllegalStateException()
                .isThrownBy(() -> provider.factsOf(RUN, cases, new AnalysisVersion("3", 1L)))
                .withMessageContaining("Analysis version 3");
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
                CaseState.SUCCEEDED,
                new CaseOutcome(EndReason.WIN_CONDITION_MET, Set.of(0), List.of(20, 15, 10), 3, 9, 30));
    }
}
