package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.workbench.experiment.domain.ExperimentValidationException;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = ExperimentController.class)
class ExperimentExceptionHandler {

    @ExceptionHandler(ExperimentValidationException.class)
    ProblemDetail handleValidation(ExperimentValidationException ex) {
        return switch (ex.code()) {
            case INVALID_EXPERIMENT -> ProblemDetails.of(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_EXPERIMENT");
            case MANIFEST_MISMATCH -> ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "MANIFEST_MISMATCH");
            case EXPERIMENT_IMMUTABLE ->
                ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "EXPERIMENT_IMMUTABLE");
            case IDEMPOTENCY_CONFLICT ->
                ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_CONFLICT");
        };
    }

    @ExceptionHandler(IdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(IdempotencyConflictException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_CONFLICT");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleMissingKey(IllegalArgumentException ex) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, ex.getMessage(), "INVALID_EXPERIMENT");
    }
}
