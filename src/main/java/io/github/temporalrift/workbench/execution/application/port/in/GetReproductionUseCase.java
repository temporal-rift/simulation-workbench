package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;

/** Reads a reproduction by its identifier. */
public interface GetReproductionUseCase {

    Optional<Reproduction> handle(UUID reproductionId);
}
