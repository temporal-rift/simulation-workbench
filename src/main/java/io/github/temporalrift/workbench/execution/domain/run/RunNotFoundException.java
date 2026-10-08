package io.github.temporalrift.workbench.execution.domain.run;

import java.util.UUID;

/** The requested run, case or experiment does not exist. */
public class RunNotFoundException extends RuntimeException {

    public RunNotFoundException(String what, UUID id) {
        super(what + " " + id + " was not found");
    }
}
