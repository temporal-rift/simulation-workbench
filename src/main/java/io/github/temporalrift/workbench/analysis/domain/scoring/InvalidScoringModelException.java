package io.github.temporalrift.workbench.analysis.domain.scoring;

/** A scoring-model request value is outside what the operation accepts. */
public class InvalidScoringModelException extends RuntimeException {

    private final String field;

    public InvalidScoringModelException(String field, String message) {
        super(message);
        this.field = field;
    }

    /** The path of the offending request value. */
    public String field() {
        return field;
    }
}
