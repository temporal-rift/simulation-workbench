package io.github.temporalrift.workbench.execution.application.port.in;

import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.Run;

/** A run with its current logical-case totals. */
public record RunView(Run run, CaseCounts counts) {}
