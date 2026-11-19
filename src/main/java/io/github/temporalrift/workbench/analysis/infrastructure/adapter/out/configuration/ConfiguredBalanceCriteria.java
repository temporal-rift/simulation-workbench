package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.Band;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionDimension;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionEvidence;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionPhase;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionPopulation;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionTable;
import io.github.temporalrift.workbench.analysis.domain.criterion.Derivation;
import io.github.temporalrift.workbench.analysis.domain.criterion.IntervalRule;
import io.github.temporalrift.workbench.analysis.domain.criterion.Pooling;
import io.github.temporalrift.workbench.analysis.domain.port.out.BalanceCriteria;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * The one balance criterion, read from {@code balance-criteria/balance-criterion.json} on the classpath. Its integer
 * {@code version} is the revision of its content; nothing creates, edits or deletes it at run time. The digest is the
 * SHA-256 of the file's canonical serialisation: object keys sorted recursively, no whitespace, array order as
 * written.
 */
public class ConfiguredBalanceCriteria implements BalanceCriteria {

    private static final String AGGREGATION = "WEIGHTED_SUM_OF_SQUARED_DEVIATIONS";
    private static final String LOCATION = "balance-criteria/balance-criterion.json";

    private final List<BalanceCriterion> criteria;

    public ConfiguredBalanceCriteria(ObjectMapper objectMapper) {
        this.criteria = List.of(read(objectMapper, new ClassPathResource(LOCATION)));
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
        return Sha256.hexUtf8(canonical(document).toString());
    }

    /** Reads a criterion document, computing its digest from the document itself. */
    public static BalanceCriterion parse(ObjectMapper objectMapper, String json) {
        return criterion(objectMapper.readTree(json));
    }

    private static BalanceCriterion read(ObjectMapper objectMapper, Resource resource) {
        try (var in = resource.getInputStream()) {
            return criterion(objectMapper.readTree(in));
        } catch (IOException e) {
            throw new UncheckedIOException("Balance criterion " + resource.getFilename() + " could not be read", e);
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
                tables(document),
                StreamSupport.stream(document.get("lines").spliterator(), false)
                        .map(ConfiguredBalanceCriteria::line)
                        .toList(),
                evidence(document.get("evidence")));
    }

    /** The tables the document declares: each seat names a policy by id. */
    private static List<CriterionTable> tables(JsonNode document) {
        return StreamSupport.stream(document.get("tables").spliterator(), false)
                .map(node -> new CriterionTable(
                        node.get("id").asString(),
                        strings(node.get("seats")),
                        strings(node.get("phases")).stream()
                                .map(CriterionPhase::valueOf)
                                .collect(Collectors.toSet())))
                .toList();
    }

    /** The populations a line declares: a policy id played on a table. */
    private static List<CriterionPopulation> populations(JsonNode node) {
        return StreamSupport.stream(node.get("populations").spliterator(), false)
                .map(population -> new CriterionPopulation(
                        population.get("table").asString(),
                        population.get("policy").asString()))
                .toList();
    }

    private static CriterionLine line(JsonNode node) {
        var band = node.get("band");
        var weight = node.get("weight");
        return new CriterionLine(
                node.get("id").asString(),
                node.get("metric").asString(),
                Derivation.valueOf(node.get("derivation").asString()),
                dimension(node.get("dimension")),
                populations(node),
                StreamSupport.stream(node.get("playerCounts").spliterator(), false)
                        .map(JsonNode::asInt)
                        .toList(),
                Pooling.valueOf(node.get("pooling").asString()),
                new Band(number(band.get("lower")), number(band.get("upper")), number(band.get("scale"))),
                interval(node.get("interval")),
                CriterionRole.valueOf(node.get("role").asString()),
                number(weight),
                node.get("rationale").asString());
    }

    /** The interval rule a line names; a line of a document that predates the rule, as in version 1, bootstraps. */
    private static IntervalRule interval(JsonNode node) {
        return node == null || node.isNull() ? IntervalRule.BLOCK_BOOTSTRAP : IntervalRule.valueOf(node.asString());
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
