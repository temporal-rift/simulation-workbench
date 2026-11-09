package io.github.temporalrift.workbench.execution.application.query;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.GetReproductionUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;

public class GetReproductionQueryHandler implements GetReproductionUseCase {

    private final ReproductionRepository reproductions;

    public GetReproductionQueryHandler(ReproductionRepository reproductions) {
        this.reproductions = reproductions;
    }

    @Override
    public Map<UUID, Reproduction> handle(Collection<UUID> reproductionIds) {
        return reproductions.findAll(reproductionIds);
    }
}
