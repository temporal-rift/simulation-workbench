package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

/** Reads one logical case of a run with its attempts and result. */
public interface GetCaseUseCase {

    CaseView handle(UUID runId, UUID caseId);
}
