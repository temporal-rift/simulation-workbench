package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.List;

/** The two sides differ in a gameplay-affecting definition, so their cases cannot be paired. */
public class IncomparableRunsException extends RuntimeException {

    private final List<String> differences;

    public IncomparableRunsException(List<String> differences) {
        super("The runs differ in " + String.join(", ", differences));
        this.differences = List.copyOf(differences);
    }

    public List<String> differences() {
        return differences;
    }
}
