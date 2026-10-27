package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckActiveException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckNotFoundException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityIdempotencyConflictException;
import io.github.temporalrift.workbench.fidelity.domain.InvalidSeedException;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = FidelityController.class)
class FidelityExceptionHandler {

    private static final String SEED = "seed";

    @ExceptionHandler(FidelityCheckNotFoundException.class)
    ProblemDetail handleNotFound(FidelityCheckNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(FidelityIdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(FidelityIdempotencyConflictException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_CONFLICT");
    }

    @ExceptionHandler(FidelityCheckActiveException.class)
    ProblemDetail handleActive(FidelityCheckActiveException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "FIDELITY_CHECK_ACTIVE");
    }

    @ExceptionHandler(SimulatorNotAvailableException.class)
    ProblemDetail handleNotAvailable(SimulatorNotAvailableException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "SIMULATOR_NOT_AVAILABLE");
    }

    /** A request body that breaks the published schema: the seed is the only value a check is started with. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        var field = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .findFirst()
                .orElse(SEED);
        return invalid("The request body is not valid: " + field, field);
    }

    @ExceptionHandler(InvalidSeedException.class)
    ProblemDetail handleInvalidSeed(InvalidSeedException ex) {
        return invalid(ex.getMessage(), SEED);
    }

    private static ProblemDetail invalid(String detail, String field) {
        var problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, detail, "INVALID_REQUEST");
        problem.setProperty("field", field);
        return problem;
    }
}
