package io.github.temporalrift.workbench.search.domain.campaign;

/** The simulator has no passing fidelity record for the source revisions it embeds, so it may not confirm. */
public class FidelityNotEstablishedException extends RuntimeException {

    public FidelityNotEstablishedException() {
        super("The simulator has no passing fidelity record for the source revisions it embeds");
    }
}
