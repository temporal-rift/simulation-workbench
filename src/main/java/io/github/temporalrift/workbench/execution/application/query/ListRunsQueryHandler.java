package io.github.temporalrift.workbench.execution.application.query;

import io.github.temporalrift.workbench.execution.application.port.in.ListRunsUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.shared.Page;

public class ListRunsQueryHandler implements ListRunsUseCase {

    private final RunRepository runs;

    public ListRunsQueryHandler(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    public Page<RunView> handle(Query query) {
        var page = runs.list(query.experimentId(), query.state(), query.limit(), query.offset());
        var counts = runs.countsOf(page.stream().map(Run::runId).toList());
        var views = page.stream()
                .map(run -> new RunView(run, counts.get(run.runId())))
                .toList();
        return new Page<>(views, runs.countRuns(query.experimentId(), query.state()));
    }
}
