package io.github.temporalrift.workbench.analysis.domain;

/** A run, experiment or comparison the request names does not exist. */
public class AnalysisResourceNotFoundException extends RuntimeException {

    public AnalysisResourceNotFoundException(String resource, Object id) {
        super(resource + " " + id + " does not exist");
    }
}
