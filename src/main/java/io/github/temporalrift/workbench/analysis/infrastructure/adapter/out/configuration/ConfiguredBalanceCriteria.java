package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.Band;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionDimension;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionEvidence;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.criterion.Derivation;
import io.github.temporalrift.workbench.analysis.domain.criterion.Pooling;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;

/**
 * The criterion versions found as {@code balance-criteria/v*.json} on the classpath. A version is added by adding a
 * file; nothing creates, edits or deletes one at run time. The digest is the SHA-256 of the file's canonical
 * serialisation: object keys sorted recursively, no whitespace, array order as written.
 */
public class ConfiguredBalanceCriteria implements BalanceCriteria {

    private static final String AGGREGATION = "WEIGHTED_SUM_OF_SQUARED_DEVIATIONS";
    private static final String LOCATION = "classpath*:balance-criteria/v*.json";

    private final List<BalanceCriterion> criteria;

    public ConfiguredBalanceCriteria(ObjectMapper objectMapper) {
        try {
            var parsed = new ArrayList<BalanceCriterion>();
            for (var resource : new PathMatchingResourcePatternResolver().getResources(LOCATION)) {
                parsed.add(read(objectMapper, resource));
            }
            parsed.sort(Comparator.comparingInt(BalanceCriterion::version));
            for (var index = 1; index < parsed.size(); index++) {
                if (parsed.get(index).version() == parsed.get(index - 1).version()) {
                    throw new IllegalStateException(
                            "Balance criterion version " + parsed.get(index).version() + " is configured twice");
                }
            }
            this.criteria = List.copyOf(parsed);
        } catch (IOException e) {
            throw new UncheckedIOException("Balance criteria could not be read", e);
        }
    }

    @Override
    public List<BalanceCriterion> all() {
        return criteria;
    }

    @Override
    public Optional<BalanceCriterion> find(int version) {
        return criteria.stream()
                .filter(criterion -> criterion.version() == version)
                .findFirst();
    }

    /** The digest of a criterion document as configured. */
    public static String digestOf(JsonNode document) {
        try {
            var bytes = canonical(document).toString().getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    /** Reads a criterion document, computing its digest from the document itself. */
    public static BalanceCriterion parse(ObjectMapper objectMapper, String json) {
        return criterion(objectMapper.readTree(json));
    }

    private static BalanceCriterion read(ObjectMapper objectMapper, Resource resource) throws IOException {
        try (var in = resource.getInputStream()) {
            return criterion(objectMapper.readTree(in));
        }
    }

    private static BalanceCriterion criterion(JsonNode document) {
        if (!AGGREGATION.equals(document.get("aggregation").asString())) {
            throw new IllegalStateException("Only the " + AGGREGATION + " aggregation is implemented");
        }
        return new BalanceCriterion(
                document.get("version").asInt(),
                digestOf(document),
                document.get("title").asString(),
                document.get("analysisVersion").asString(),
                StreamSupport.stream(document.get("lines").spliterator(), false)
                        .map(ConfiguredBalanceCriteria::line)
                        .toList(),
                evidence(document.get("evidence")));
    }

    private static CriterionLine line(JsonNode node) {
        var band = node.get("band");
        var weight = node.get("weight");
        return new CriterionLine(
                node.get("id").asString(),
                node.get("metric").asString(),
                Derivation.valueOf(node.get("derivation").asString()),
                dimension(node.get("dimension")),
                strings(node.get("policies")),
                StreamSupport.stream(node.get("playerCounts").spliterator(), false)
                        .map(JsonNode::asInt)
                        .toList(),
                Pooling.valueOf(node.get("pooling").asString()),
                new Band(number(band.get("lower")), number(band.get("upper")), number(band.get("scale"))),
                CriterionRole.valueOf(node.get("role").asString()),
                number(weight),
                node.get("rationale").asString());
    }

    private static CriterionDimension dimension(JsonNode node) {
        var endReason = node.get("endReason");
        var codes = node.get("failureCodes");
        return new CriterionDimension(
                node.get("eachFaction").asBoolean(),
                endReason == null || endReason.isNull() ? null : EndReason.valueOf(endReason.asString()),
                number(node.get("quantile")),
                codes == null || codes.isNull()
                        ? null
                        : strings(codes).stream().map(FailureCode::valueOf).toList());
    }

    private static CriterionEvidence evidence(JsonNode node) {
        return new CriterionEvidence(
                node.get("confidenceLevel").asDouble(),
                node.get("executionFailedShareLimit").asDouble(),
                strings(node.get("invalidatingFailureCodes")).stream()
                        .map(FailureCode::valueOf)
                        .collect(Collectors.toSet()),
                strings(node.get("invalidatingEndReasons")).stream()
                        .map(EndReason::valueOf)
                        .collect(Collectors.toSet()),
                node.get("confirmationMinimumSeeds").asInt());
    }

    private static List<String> strings(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asString)
                .toList();
    }

    private static Double number(JsonNode node) {
        return node == null || node.isNull() ? null : node.asDouble();
    }

    private static JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var sorted = new TreeMap<String, JsonNode>();
            object.properties().forEach(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            var result = JsonNodeFactory.instance.objectNode();
            sorted.forEach(result::set);
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        return node.isNumber() ? JsonNodeFactory.instance.numberNode(numeric(node)) : node;
    }

    /** Numbers are hashed by value, so 2 and 2.0 are the same number. */
    private static BigDecimal numeric(JsonNode node) {
        var value = node.decimalValue();
        return value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
    }
}
