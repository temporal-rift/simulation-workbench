package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.FactExtractor;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.CaseFactsRepository;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;

/** The facts of a run's succeeded cases, extracting and storing each case once per analysis version. */
public class CaseFactsProvider {

    /** How many case ids one stored-facts lookup carries. */
    static final int LOOKUP_CHUNK = 1000;

    private final RunSource runs;
    private final CaseFactsRepository facts;

    public CaseFactsProvider(RunSource runs, CaseFactsRepository facts) {
        this.runs = runs;
        this.facts = facts;
    }

    /**
     * @param analysis the version the caller computes under, such as the one a comparison pinned at creation
     * @throws IllegalStateException when the version is not one this workbench implements, rather than
     *     answering a pinned version with another version's facts
     */
    public Map<UUID, CaseFacts> factsOf(UUID runId, List<AnalyzedCase> cases, AnalysisVersion analysis) {
        if (!analysis.supported()) {
            throw new IllegalStateException("Analysis version " + analysis.version() + " is not implemented");
        }
        var succeeded = cases.stream().filter(AnalyzedCase::succeeded).toList();
        var stored = new HashMap<UUID, CaseFacts>();
        for (var from = 0; from < succeeded.size(); from += LOOKUP_CHUNK) {
            var chunk = succeeded.subList(from, Math.min(from + LOOKUP_CHUNK, succeeded.size()));
            stored.putAll(facts.find(chunk.stream().map(AnalyzedCase::caseId).toList(), analysis.factsVersion()));
        }
        var extracted = new ArrayList<CaseFacts>();
        for (var analyzedCase : succeeded) {
            if (!stored.containsKey(analyzedCase.caseId())) {
                runs.countingGame(runId, analyzedCase).ifPresent(gameRecord -> {
                    var caseFacts = FactExtractor.extract(
                            analyzedCase.caseId(), analyzedCase.seatFactions(), analyzedCase.outcome(), gameRecord);
                    extracted.add(caseFacts);
                    stored.put(analyzedCase.caseId(), caseFacts);
                });
            }
        }
        if (!extracted.isEmpty()) {
            facts.saveAll(extracted, analysis.factsVersion());
        }
        return stored;
    }
}
