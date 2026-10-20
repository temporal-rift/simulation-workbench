package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Constraint;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Derived;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.HeldFixed;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;

/**
 * The parameter space versions found as {@code parameter-spaces/v*.json} on the classpath. A version is added by
 * adding a file; nothing creates, edits or deletes one at run time.
 */
public class ConfiguredParameterSpaces implements ParameterSpaces {

    private static final String LOCATION = "classpath*:parameter-spaces/v*.json";

    private final List<ParameterSpace> spaces;

    public ConfiguredParameterSpaces(ObjectMapper objectMapper) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        } catch (IOException e) {
            throw new UncheckedIOException("Parameter spaces could not be listed", e);
        }
        var parsed = Arrays.stream(resources)
                .map(resource -> read(objectMapper, resource))
                .sorted(Comparator.comparingInt(ParameterSpace::version))
                .toList();
        if (parsed.stream().map(ParameterSpace::version).distinct().count() != parsed.size()) {
            throw new IllegalStateException("A parameter space version is configured twice");
        }
        this.spaces = parsed;
    }

    @Override
    public List<ParameterSpace> all() {
        return spaces;
    }

    @Override
    public Optional<ParameterSpace> find(int version) {
        return spaces.stream().filter(space -> space.version() == version).findFirst();
    }

    private static ParameterSpace read(ObjectMapper objectMapper, Resource resource) {
        try (var in = resource.getInputStream()) {
            return space(objectMapper.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Parameter space " + resource.getFilename() + " could not be read", e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Parameter space " + resource.getFilename() + " is invalid", e);
        }
    }

    static ParameterSpace space(JsonNode document) {
        return new ParameterSpace(
                document.get("version").asInt(),
                list(document, "parameters").stream()
                        .map(node -> new Parameter(
                                node.get("key").asString(),
                                strings(node.get("services")),
                                node.get("baseline").decimalValue(),
                                node.get("min").decimalValue(),
                                node.get("max").decimalValue(),
                                node.get("step").decimalValue()))
                        .toList(),
                list(document, "derived").stream()
                        .map(node -> new Derived(
                                node.get("key").asString(),
                                node.get("from").asString(),
                                node.get("factor").decimalValue()))
                        .toList(),
                list(document, "constraints").stream()
                        .map(node -> Constraint.of(
                                node.get("id").asString(),
                                node.get("expression").asString(),
                                node.get("description").asString()))
                        .toList(),
                list(document, "heldFixed").stream()
                        .map(node -> new HeldFixed(
                                node.get("key").asString(), node.get("reason").asString()))
                        .toList());
    }

    private static List<JsonNode> list(JsonNode document, String field) {
        return StreamSupport.stream(document.get(field).spliterator(), false).toList();
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }
}
