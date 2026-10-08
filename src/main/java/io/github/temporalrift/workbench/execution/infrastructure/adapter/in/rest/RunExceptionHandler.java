package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = RunController.class)
class RunExceptionHandler {

    @ExceptionHandler(InvalidRunStateException.class)
    ProblemDetail handleInvalidState(InvalidRunStateException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "INVALID_RUN_STATE");
    }

    @ExceptionHandler(RunIdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(RunIdempotencyConflictException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_CONFLICT");
    }

    @ExceptionHandler(RunNotFoundException.class)
    ProblemDetail handleNotFound(RunNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "NOT_FOUND");
    }

    @ExceptionHandler(OperationNotAvailableException.class)
    ProblemDetail handleNotAvailable(OperationNotAvailableException ex) {
        return ProblemDetails.of(HttpStatus.NOT_IMPLEMENTED, ex.getMessage(), "NOT_IMPLEMENTED");
    }
}
