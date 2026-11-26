package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

/** Answers every refused scoring-model request with {@code INVALID_REQUEST} naming the offending value. */
@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = ScoringModelController.class)
class ScoringModelExceptionHandler {

    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    @ExceptionHandler(InvalidScoringModelException.class)
    ProblemDetail handleInvalid(InvalidScoringModelException ex) {
        return invalid(ex.getMessage(), ex.field());
    }

    @ExceptionHandler(AnalysisResourceNotFoundException.class)
    ProblemDetail handleNotFound(AnalysisResourceNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleConstraint(MethodArgumentNotValidException ex) {
        var error = ex.getBindingResult().getFieldErrors().stream().findFirst();
        var field = error.map(FieldError::getField).orElse("body");
        var message = error.map(item -> item.getDefaultMessage()).orElse("The request is invalid");
        return invalid(message, field);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail handleUnreadable(HttpMessageNotReadableException ex) {
        return invalid("The request body is malformed", "body");
    }

    private static ProblemDetail invalid(String detail, String field) {
        var problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, detail, INVALID_REQUEST);
        problem.setProperty("field", field);
        return problem;
    }
}
