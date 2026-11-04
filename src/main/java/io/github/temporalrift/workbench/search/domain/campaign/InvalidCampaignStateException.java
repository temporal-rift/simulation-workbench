package io.github.temporalrift.workbench.search.domain.campaign;

/** An operation a campaign does not accept in its current state. */
public class InvalidCampaignStateException extends RuntimeException {

    private final transient CampaignState state;

    public InvalidCampaignStateException(String operation, CampaignState state) {
        super(operation + " is not legal for a campaign in state " + state);
        this.state = state;
    }

    public CampaignState state() {
        return state;
    }
}
