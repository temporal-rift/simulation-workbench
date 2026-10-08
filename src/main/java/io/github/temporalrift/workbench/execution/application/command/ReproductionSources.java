package io.github.temporalrift.workbench.execution.application.command;

import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;

/** Where a reproduction reads the saved case from: its run, its frozen experiment, and its retained evidence. */
public record ReproductionSources(RunRepository runs, ExperimentSource experiments, EvidenceLedger evidence) {}
