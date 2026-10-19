package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;

/** The balance criterion versions the workbench serves. They are configuration, never created through the API. */
public interface BalanceCriteria {

    /** Every served version in ascending version order. */
    List<BalanceCriterion> all();

    Optional<BalanceCriterion> find(int version);
}
