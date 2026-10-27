package io.github.temporalrift.workbench.fidelity.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;

/** Reads one fidelity check. */
public interface GetFidelityCheckUseCase {

    /** @throws io.github.temporalrift.workbench.fidelity.domain.FidelityCheckNotFoundException when unknown */
    FidelityCheckView handle(UUID checkId);
}
