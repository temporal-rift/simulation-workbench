package io.github.temporalrift.workbench.analysis.application.query;

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

    private final RunSource runs;
    private final CaseFactsRepository facts;

    public CaseFactsProvider(RunSource runs, CaseFactsRepository facts) {
        this.runs = runs;
        this.facts = facts;
    }

    /**
     * @param analysis the version the caller computes under, such as the one a comparison pinned at creation
     * @throws IllegalStateException when the version is not the one this workbench implements, rather than
     *     answering a pinned version with another version's facts
     */
    public Map<UUID, CaseFacts> factsOf(UUID runId, List<AnalyzedCase> cases, AnalysisVersion analysis) {
        if (!AnalysisVersion.CURRENT.equals(analysis)) {
            throw new IllegalStateException("Analysis version " + analysis.version() + " is not implemented");
        }
        var succeeded = cases.stream().filter(AnalyzedCase::succeeded).toList();
        var stored = new HashMap<>(
                facts.find(succeeded.stream().map(AnalyzedCase::caseId).toList(), analysis.version()));
        for (var analyzedCase : succeeded) {
            if (!stored.containsKey(analyzedCase.caseId())) {
                runs.record(runId, analyzedCase).ifPresent(record -> {
                    var extracted = FactExtractor.extract(
                            analyzedCase.caseId(), analyzedCase.seatFactions(), analyzedCase.outcome(), record);
                    facts.save(extracted, analysis.version());
                    stored.put(analyzedCase.caseId(), extracted);
                });
            }
        }
        return stored;
    }
}
