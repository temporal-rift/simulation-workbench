package io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.StreamSupport;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.BooleanParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Constraint;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Derived;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.HeldFixed;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.NumberParameter;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;

/**
 * The one parameter space, read from {@code parameter-spaces/parameter-space.json} on the classpath. Its integer
 * {@code version} is the revision of its content; nothing creates, edits or deletes it at run time.
 */
public class ConfiguredParameterSpaces implements ParameterSpaces {

    private static final String LOCATION = "parameter-spaces/parameter-space.json";

    private final List<ParameterSpace> spaces;

    public ConfiguredParameterSpaces(ObjectMapper objectMapper) {
        this.spaces = List.of(read(objectMapper, new ClassPathResource(LOCATION)));
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
                        .map(ConfiguredParameterSpaces::parameter)
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

    private static Parameter parameter(JsonNode node) {
        var key = node.get("key").asString();
        var services = strings(node.get("services"));
        var type = node.has("type") ? node.get("type").asString() : "NUMBER";
        return switch (type) {
            case "NUMBER" ->
                new NumberParameter(
                        key,
                        services,
                        node.get("baseline").decimalValue(),
                        node.get("min").decimalValue(),
                        node.get("max").decimalValue(),
                        node.get("step").decimalValue());
            case "BOOLEAN" ->
                new BooleanParameter(key, services, node.get("baseline").asBoolean());
            default -> throw new IllegalArgumentException(key + " has an unknown type " + type);
        };
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
