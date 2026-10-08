package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

/** A published operation whose behavior is not delivered yet. */
class OperationNotAvailableException extends RuntimeException {

    OperationNotAvailableException(String operation) {
        super(operation + " is not available yet");
    }
}
