package io.github.temporalrift.workbench.analysis.domain;

import java.util.UUID;

/** A run, experiment or comparison the request names does not exist. */
public class AnalysisResourceNotFoundException extends RuntimeException {

    public AnalysisResourceNotFoundException(String resource, UUID id) {
        super(resource + " " + id + " does not exist");
    }
}
