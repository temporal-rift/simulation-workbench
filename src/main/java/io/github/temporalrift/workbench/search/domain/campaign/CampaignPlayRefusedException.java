package io.github.temporalrift.workbench.search.domain.campaign;

/** The experiment or run a wave needs was refused. */
public class CampaignPlayRefusedException extends RuntimeException {

    public CampaignPlayRefusedException(String message, Throwable cause) {
        super(message, cause);
    }

    public CampaignPlayRefusedException(String message) {
        super(message);
    }
}
