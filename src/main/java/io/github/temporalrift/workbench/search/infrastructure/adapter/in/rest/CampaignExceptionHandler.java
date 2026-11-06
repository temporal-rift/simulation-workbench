package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException;
import io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException;
import io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.ProblemDetails;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.RestAdviceOrder;

@Order(RestAdviceOrder.MODULE)
@RestControllerAdvice(basePackageClasses = CampaignController.class)
class CampaignExceptionHandler {

    @ExceptionHandler(CampaignNotFoundException.class)
    ProblemDetail handleNotFound(CampaignNotFoundException ex) {
        return ProblemDetails.of(HttpStatus.NOT_FOUND, ex.getMessage(), "RESOURCE_NOT_FOUND");
    }

    @ExceptionHandler(CampaignIdempotencyConflictException.class)
    ProblemDetail handleIdempotencyConflict(CampaignIdempotencyConflictException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "IDEMPOTENCY_CONFLICT");
    }

    @ExceptionHandler(InvalidCampaignStateException.class)
    ProblemDetail handleInvalidState(InvalidCampaignStateException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "INVALID_CAMPAIGN_STATE");
    }

    @ExceptionHandler(InvalidCampaignException.class)
    ProblemDetail handleInvalidCampaign(InvalidCampaignException ex) {
        return invalid(ex.getMessage(), ex.field(), ex.reason());
    }

    /** A request body that breaks the published schema. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail handleInvalidBody(MethodArgumentNotValidException ex) {
        var field = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField)
                .findFirst()
                .orElse(null);
        return invalid(
                "The request body is not valid" + (field == null ? "" : ": " + field),
                field,
                InvalidCampaignReason.INVALID_VALUE);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleInvalidValue(IllegalArgumentException ex) {
        return invalid(ex.getMessage(), null, InvalidCampaignReason.INVALID_VALUE);
    }

    @ExceptionHandler(SeedOverlapException.class)
    ProblemDetail handleSeedOverlap(SeedOverlapException ex) {
        return ProblemDetails.of(HttpStatus.CONFLICT, ex.getMessage(), "SEED_OVERLAP");
    }

    @ExceptionHandler(CampaignPlayRefusedException.class)
    ProblemDetail handlePlayRefused(CampaignPlayRefusedException ex) {
        return ProblemDetails.of(HttpStatus.UNPROCESSABLE_CONTENT, ex.getMessage(), "CAMPAIGN_PLAY_REFUSED");
    }

    @ExceptionHandler(NoRealLaneException.class)
    ProblemDetail handleNoRealLane(NoRealLaneException ex) {
        return ProblemDetails.of(HttpStatus.SERVICE_UNAVAILABLE, ex.getMessage(), "NO_REAL_LANE");
    }

    private static ProblemDetail invalid(String detail, String field, InvalidCampaignReason reason) {
        var problem = ProblemDetails.of(HttpStatus.BAD_REQUEST, detail, "INVALID_CAMPAIGN");
        if (field != null) {
            problem.setProperty("field", field);
        }
        problem.setProperty("reason", reason.name());
        return problem;
    }
}
