package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;

/**
 * Some variant of the experiment has no configured lane serving its bundle, so its cases could never play (published
 * as {@code MANIFEST_MISMATCH}).
 */
public class UnservedVariantsException extends RuntimeException {

    private final List<String> labels;

    public UnservedVariantsException(List<String> labels) {
        super("No configured lane serves the rules and content of variants: " + String.join(", ", labels));
        this.labels = List.copyOf(labels);
    }

    public List<String> labels() {
        return labels;
    }
}
