package io.github.temporalrift.workbench.analysis.domain.comparison;

/** The comparison request names an unknown variant or the same side twice. */
public class InvalidComparisonException extends RuntimeException {

    public InvalidComparisonException(String message) {
        super(message);
    }
}
