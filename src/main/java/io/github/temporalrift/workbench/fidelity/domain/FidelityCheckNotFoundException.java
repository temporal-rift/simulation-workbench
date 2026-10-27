package io.github.temporalrift.workbench.fidelity.domain;

import java.util.UUID;

/** The fidelity check does not exist (published as RESOURCE_NOT_FOUND). */
public class FidelityCheckNotFoundException extends RuntimeException {

    public FidelityCheckNotFoundException(UUID id) {
        super("Fidelity check " + id + " does not exist");
    }
}
