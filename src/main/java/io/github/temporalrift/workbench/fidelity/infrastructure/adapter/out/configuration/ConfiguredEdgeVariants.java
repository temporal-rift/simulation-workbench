package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.TreeMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.fidelity.domain.EdgeVariantCatalog;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** The edge variants shipped as {@code fidelity/edge-variants.json}, for the one served parameter space. */
public final class ConfiguredEdgeVariants {

    static final String LOCATION = "fidelity/edge-variants.json";

    private ConfiguredEdgeVariants() {}

    public static EdgeVariantCatalog load(ObjectMapper objectMapper) {
        return new EdgeVariantCatalog(List.of(read(objectMapper, new ClassPathResource(LOCATION))));
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
