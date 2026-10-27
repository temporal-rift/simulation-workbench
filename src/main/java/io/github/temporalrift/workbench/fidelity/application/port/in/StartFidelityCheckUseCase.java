package io.github.temporalrift.workbench.fidelity.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;

/** Accepts a fidelity check for a seed, once per idempotency key. */
public interface StartFidelityCheckUseCase {

    /**
     * @throws io.github.temporalrift.workbench.fidelity.domain.FidelityCheckActiveException when another check is open
     * @throws io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException when the simulator is
     *     not enabled or no real lane is build-equivalent to it
     * @throws io.github.temporalrift.workbench.fidelity.domain.FidelityIdempotencyConflictException when the key
     *     was used for another seed
     */
    FidelityCheckView handle(Command command);

    record Command(UUID idempotencyKey, String seed) {}
}
