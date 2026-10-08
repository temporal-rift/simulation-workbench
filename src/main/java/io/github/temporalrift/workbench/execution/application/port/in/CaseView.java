package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;

/** A logical case with every attempt that executed or recovered it. */
public record CaseView(LogicalCase logicalCase, List<Attempt> attempts) {

    public CaseView {
        attempts = List.copyOf(attempts);
    }
}
