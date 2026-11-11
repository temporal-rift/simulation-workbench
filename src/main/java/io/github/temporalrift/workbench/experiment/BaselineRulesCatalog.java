package io.github.temporalrift.workbench.experiment;

import java.util.List;
import java.util.Optional;

/**
 * The baseline rules documents the configured lanes publish, which a variant names as the rules it overrides. The
 * experiment module declares what it needs to know; the module that owns the lanes supplies it.
 */
public interface BaselineRulesCatalog {

    /** The baseline document whose rules artifact has this digest, or empty when no lane serves one. */
    Optional<Baseline> find(String rulesArtifactDigest);

    /** Every baseline document the deployment declares, each with the setting that names where it is configured. */
    List<Declared> declared();

    /**
     * A declared baseline document.
     *
     * @param scope the property prefix naming the deployment that declares it
     * @param baseline the document
     */
    record Declared(String scope, Baseline baseline) {}

    /**
     * A baseline document as the lane publishes it.
     *
     * @param document the document's text, retained verbatim when an experiment is frozen
     * @param rules the effective rules the document holds
     */
    record Baseline(String document, EffectiveRules rules) {}
}
