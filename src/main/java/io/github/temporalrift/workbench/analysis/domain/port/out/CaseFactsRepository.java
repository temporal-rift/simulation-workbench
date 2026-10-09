package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;

/** Facts already extracted per case and analysis version; a case's facts never change once stored. */
public interface CaseFactsRepository {

    Map<UUID, CaseFacts> find(Collection<UUID> caseIds, String analysisVersion);

    /** Stores the facts unless the case already has facts for the version. */
    void save(CaseFacts facts, String analysisVersion);
}
