package io.github.temporalrift.workbench.policy.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.workbench.policy.domain.PolicyParameterSpaceNotFoundException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = PolicyParameterSpaceController.class)
class PolicyExceptionHandler {

    @ExceptionHandler(PolicyParameterSpaceNotFoundException.class)
    ProblemDetail handleNotFound(PolicyParameterSpaceNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "RESOURCE_NOT_FOUND");
    }
}
