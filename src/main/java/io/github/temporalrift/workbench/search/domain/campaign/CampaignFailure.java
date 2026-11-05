package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.Objects;

/** Why a campaign has not started or stopped with an error, as a code and a message. */
public record CampaignFailure(String code, String message) {

    /** The simulator has no passing fidelity record for the source revisions it embeds. */
    public static final String FIDELITY_NOT_ESTABLISHED = "FIDELITY_NOT_ESTABLISHED";

    /** The parameter space cannot supply as many distinct valid candidates as a round asks for. */
    public static final String CANDIDATE_GENERATION_EXHAUSTED = "CANDIDATE_GENERATION_EXHAUSTED";

    /** A confirmation run failed or was cancelled. */
    public static final String CONFIRMATION_RUN_FAILED = "CONFIRMATION_RUN_FAILED";

    /** The confirmation of a finished run could not be evaluated. */
    public static final String CONFIRMATION_EVALUATION_FAILED = "CONFIRMATION_EVALUATION_FAILED";

    public CampaignFailure {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }
}
