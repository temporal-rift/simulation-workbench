package io.github.temporalrift.workbench.analysis.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;

/** Lists every served balance criterion version in version order. */
public interface ListBalanceCriteriaUseCase {

    List<BalanceCriterion> handle();
}
