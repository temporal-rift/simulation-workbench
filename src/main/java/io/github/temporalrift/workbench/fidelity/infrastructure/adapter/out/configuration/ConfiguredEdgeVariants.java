package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.TreeMap;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.fidelity.domain.EdgeVariantCatalog;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** The edge variants shipped as {@code fidelity/edge-variants-v<n>.json}, one resource per parameter space. */
public final class ConfiguredEdgeVariants {

    static final String PATTERN = "classpath*:fidelity/edge-variants-v*.json";

    private ConfiguredEdgeVariants() {}

    public static EdgeVariantCatalog load(ObjectMapper objectMapper) {
        try {
            var resources = new PathMatchingResourcePatternResolver().getResources(PATTERN);
            var variants = Arrays.stream(resources)
                    .map(resource -> read(objectMapper, resource))
                    .toList();
            return new EdgeVariantCatalog(variants);
        } catch (IOException e) {
            throw new UncheckedIOException(PATTERN + " could not be listed", e);
        }
    }

    private static EdgeVariants read(ObjectMapper objectMapper, Resource resource) {
        try (InputStream in = resource.getInputStream()) {
            var document = objectMapper.readTree(in);
            var variants = document.required("variants");
            return new EdgeVariants(
                    document.required("parameterSpaceVersion").asInt(),
                    overrides(variants.required("low")),
                    overrides(variants.required("high")));
        } catch (IOException e) {
            throw new UncheckedIOException(resource.getFilename() + " could not be read", e);
        }
    }

    private static TreeMap<String, ParameterValue> overrides(JsonNode node) {
        var overrides = new TreeMap<String, ParameterValue>();
        node.properties().forEach(entry -> overrides.put(entry.getKey(), ParameterValue.of(entry.getValue())));
        return overrides;
    }
}
