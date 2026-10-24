package io.github.temporalrift.workbench.experiment;

import java.util.List;

/** The bounds of a frozen experiment together with its cases in stable order, read and parsed once. */
public record ExperimentSnapshot(ExperimentBounds bounds, List<ExperimentCase> cases) {

    public ExperimentSnapshot {
        cases = List.copyOf(cases);
    }
}
