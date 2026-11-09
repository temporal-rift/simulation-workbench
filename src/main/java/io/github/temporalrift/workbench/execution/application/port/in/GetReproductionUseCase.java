package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;

/** Reads reproductions by their identifiers. */
public interface GetReproductionUseCase {

    /** The reproductions that exist among {@code reproductionIds}, by identifier. */
    Map<UUID, Reproduction> handle(Collection<UUID> reproductionIds);
}
