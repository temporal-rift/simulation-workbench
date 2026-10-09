package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.comparison.ExperimentDefinition;

/** Frozen experiments as analysis compares and attributes them. */
public interface ExperimentDefinitions {

    Optional<FrozenExperiment> find(UUID experimentId);

    /** A frozen experiment's manifest digest and gameplay definition. */
    record FrozenExperiment(String manifestDigest, ExperimentDefinition definition) {}
}
