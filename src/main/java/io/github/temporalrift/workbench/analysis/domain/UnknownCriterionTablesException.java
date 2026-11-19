package io.github.temporalrift.workbench.analysis.domain;

import java.util.Collection;

/**
 * A run played tables the balance criterion does not list, so evaluating it would return lines with no data. It names
 * the tables the run played that the criterion does not know and the ones the criterion lists.
 */
public class UnknownCriterionTablesException extends RuntimeException {

    public UnknownCriterionTablesException(Collection<String> unknown, Collection<String> known) {
        super("The run played tables the balance criterion does not list: " + String.join(", ", unknown)
                + "; the criterion lists: " + String.join(", ", known));
    }
}
