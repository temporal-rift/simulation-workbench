package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.rules;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.BaselineRulesCatalog;
import io.github.temporalrift.workbench.experiment.EffectiveRules;

/**
 * The baseline rules documents the configured lanes and the simulator declare. Every document is read and digested at
 * construction, so a deployment whose document is missing, malformed, or not the rules it declares fails to start, and
 * the failure names the setting.
 */
public class ConfiguredBaselineRules implements BaselineRulesCatalog {

    /**
     * What a deployment declares about its baseline.
     *
     * @param scope the property prefix naming the deployment, such as {@code workbench.execution.lanes[id=lane-1].}
     * @param rulesArtifactDigest the digest of the rules artifact a variant names to select the baseline
     * @param rulesDigest the effective rules digest the deployment serves
     * @param document where the baseline document is
     */
    public record Declaration(String scope, String rulesArtifactDigest, String rulesDigest, Path document) {}

    private final Map<String, Baseline> byArtifact = new HashMap<>();

    public ConfiguredBaselineRules(List<Declaration> declarations, ObjectMapper objectMapper) {
        declarations.forEach(declaration -> add(declaration, read(declaration, objectMapper)));
    }

    @Override
    public Optional<Baseline> find(String rulesArtifactDigest) {
        return Optional.ofNullable(byArtifact.get(rulesArtifactDigest));
    }

    private static Baseline read(Declaration declaration, ObjectMapper objectMapper) {
        var location = declaration.scope() + "rules-document";
        String text;
        try {
            text = Files.readString(declaration.document());
        } catch (IOException e) {
            throw new UncheckedIOException(location + " could not be read: " + declaration.document(), e);
        }
        EffectiveRules rules;
        try {
            rules = EffectiveRules.parse(objectMapper.readTree(text));
        } catch (JacksonException | IllegalArgumentException e) {
            throw new IllegalArgumentException(location + " is not an EffectiveRules document: " + e.getMessage(), e);
        }
        if (!rules.digest().equals(declaration.rulesDigest())) {
            throw new IllegalArgumentException(location + " has effective rules digest " + rules.digest()
                    + ", which differs from the declared rules-digest " + declaration.rulesDigest());
        }
        return new Baseline(text, rules);
    }

    private void add(Declaration declaration, Baseline baseline) {
        var earlier = byArtifact.putIfAbsent(declaration.rulesArtifactDigest(), baseline);
        if (earlier != null && !earlier.rules().digest().equals(baseline.rules().digest())) {
            throw new IllegalArgumentException(declaration.scope() + "rules-document differs from another declaration "
                    + "of the rules artifact " + declaration.rulesArtifactDigest());
        }
    }
}
