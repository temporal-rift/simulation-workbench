package io.github.temporalrift.workbench.execution.application.query;

import io.github.temporalrift.workbench.execution.application.port.in.ListCasesUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.shared.Page;

public class ListCasesQueryHandler implements ListCasesUseCase {

    private final RunRepository runs;

    public ListCasesQueryHandler(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    public Page<LogicalCase> handle(Query query) {
        runs.find(query.runId()).orElseThrow(() -> new RunNotFoundException("Run", query.runId()));
        return new Page<>(
                runs.listCases(query.runId(), query.state(), query.variantLabel(), query.limit(), query.offset()),
                runs.countCases(query.runId(), query.state(), query.variantLabel()));
    }
}
