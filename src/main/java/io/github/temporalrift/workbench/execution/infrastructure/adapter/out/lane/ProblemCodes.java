package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;

/** Classifies what a failed participant call tells us about whether the service holds the command. */
final class ProblemCodes {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ProblemCodes() {}

    /**
     * A definite answer from the service is a rejection carrying its problem code; anything else (a lost
     * response, a timeout, a server error) leaves the outcome unknown and must be reconciled.
     */
    static SubmissionOutcome outcomeOf(RestClientException failure) {
        if (failure instanceof RestClientResponseException response
                && response.getStatusCode().is4xxClientError()) {
            return new SubmissionOutcome.Rejected(codeOf(response));
        }
        return new SubmissionOutcome.Unacknowledged();
    }

    /** The published problem {@code code}, or the HTTP status when the body carries none. */
    static String codeOf(RestClientResponseException response) {
        try {
            JsonNode body = JSON.readTree(response.getResponseBodyAsString());
            var code = body.get("code");
            if (code != null && code.isTextual()) {
                return code.asString();
            }
        } catch (RuntimeException _) {
            // A body that is not JSON carries no code; the status still identifies the rejection.
        }
        return String.valueOf(response.getStatusCode().value());
    }
}
