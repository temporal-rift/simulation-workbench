package io.github.temporalrift.workbench.execution.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.CaseView;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

public class GetCaseQueryHandler implements GetCaseUseCase {

    private final RunRepository runs;

    public GetCaseQueryHandler(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    public CaseView handle(UUID runId, UUID caseId) {
        var logicalCase = runs.findCase(runId, caseId).orElseThrow(() -> new RunNotFoundException("Case", caseId));
        return new CaseView(logicalCase, runs.attemptsOf(caseId));
    }
}
