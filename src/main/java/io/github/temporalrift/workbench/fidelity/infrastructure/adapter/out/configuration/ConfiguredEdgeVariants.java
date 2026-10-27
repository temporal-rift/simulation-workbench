package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.TreeMap;

import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;

/** The edge variants shipped as {@code fidelity/edge-variants-v1.json}. */
public final class ConfiguredEdgeVariants {

    static final String RESOURCE = "fidelity/edge-variants-v1.json";

    private ConfiguredEdgeVariants() {}

    public static EdgeVariants load(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            var document = objectMapper.readTree(in);
            var variants = document.required("variants");
            return new EdgeVariants(
                    document.required("parameterSpaceVersion").asInt(),
                    overrides(variants.required("low")),
                    overrides(variants.required("high")));
        } catch (IOException e) {
            throw new UncheckedIOException(RESOURCE + " could not be read", e);
        }
    }

    private static TreeMap<String, BigDecimal> overrides(JsonNode node) {
        var overrides = new TreeMap<String, BigDecimal>();
        node.properties()
                .forEach(entry -> overrides.put(entry.getKey(), entry.getValue().decimalValue()));
        return overrides;
    }
}
