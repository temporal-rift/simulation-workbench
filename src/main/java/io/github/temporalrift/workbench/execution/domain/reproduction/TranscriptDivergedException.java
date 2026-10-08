package io.github.temporalrift.workbench.execution.domain.reproduction;

/** The reproduction could not follow the saved transcript, so it stopped at its first divergence. */
public class TranscriptDivergedException extends RuntimeException {

    private final transient Divergence divergence;

    public TranscriptDivergedException(Divergence divergence) {
        super("The reproduction diverged at step " + divergence.step() + ": " + divergence.kind());
        this.divergence = divergence;
    }

    public Divergence divergence() {
        return divergence;
    }
}
