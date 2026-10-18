package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

/** A request parameter of the evaluation operation is outside its published values. */
class InvalidEvaluationRequestException extends RuntimeException {

    InvalidEvaluationRequestException(String message) {
        super(message);
    }
}
