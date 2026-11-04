package io.github.temporalrift.workbench.search.domain.campaign;

/** A campaign definition that cannot be searched, with the value that is wrong and why. */
public class InvalidCampaignException extends RuntimeException {

    private final transient InvalidCampaignReason reason;
    private final String field;

    /**
     * @param field the path of the offending value, or null when it is not attributable to one
     */
    public InvalidCampaignException(InvalidCampaignReason reason, String field, String message) {
        super(message);
        this.reason = reason;
        this.field = field;
    }

    public InvalidCampaignReason reason() {
        return reason;
    }

    public String field() {
        return field;
    }
}
