package io.github.temporalrift.workbench.search.domain.campaign;

/** The balance criterion could not evaluate the candidates of a round. */
public class CampaignEvaluationException extends RuntimeException {

    public CampaignEvaluationException(String message, Throwable cause) {
        super(message, cause);
    }
}
