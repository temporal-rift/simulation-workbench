package io.github.temporalrift.workbench.search.domain.campaign;

/** No policy bundle serves a policy that something a campaign plays names. */
public class PolicyNotAvailableException extends CampaignPlayRefusedException {

    public PolicyNotAvailableException(String message) {
        super(message);
    }
}
