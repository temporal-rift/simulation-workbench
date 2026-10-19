package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.comparison.ComparisonIdempotencyConflictException;
import io.github.temporalrift.workbench.analysis.domain.comparison.IncomparableRunsException;
import io.github.temporalrift.workbench.analysis.domain.comparison.InvalidComparisonException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = ReportController.class)
class AnalysisExceptionHandler {

    private static final String INVALID_COMPARISON = "INVALID_COMPARISON";
    private static final String INVALID_REQUEST = "INVALID_REQUEST";

    @ExceptionHandler(AnalysisResourceNotFoundException.class)
    ProblemDetail handleNotFound(AnalysisResourceNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(InvalidComparisonException.class)
    ProblemDetail handleInvalid(InvalidComparisonException ex) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, ex.getMessage(), INVALID_COMPARISON);
    }

    @ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
    ProblemDetail handleMalformed(Exception ex) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, "The comparison request is malformed", INVALID_COMPARISON);
    }

    @ExceptionHandler(IncomparableRunsException.class)
    ProblemDetail handleIncomparable(IncomparableRunsException ex) {
        return ProblemDetails.of(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), "INCOMPARABLE_RUNS");
    }

    @ExceptionHandler(ComparisonIdempotencyConflictException.class)
    ProblemDetail handleConflict(ComparisonIdempotencyConflictException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_CONFLICT");
    }

    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    ProblemDetail handleBadParameter(Exception ex) {
        return ProblemDetails.of(
                HttpStatus.BAD_REQUEST, "The request parameters are missing or malformed", INVALID_REQUEST);
    }

    @ExceptionHandler(InvalidEvaluationRequestException.class)
    ProblemDetail handleInvalidEvaluation(InvalidEvaluationRequestException ex) {
        return ProblemDetails.of(HttpStatus.BAD_REQUEST, ex.getMessage(), INVALID_REQUEST);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ProblemDetail handleInsufficientScope(AccessDeniedException ex) {
        return ProblemDetails.of(HttpStatus.FORBIDDEN, "Access denied", "INSUFFICIENT_SCOPE");
    }
}
