package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
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
 * The criterion versions found as {@code balance-criteria/v*.json} on the classpath. A version is added by adding a
 * file; nothing creates, edits or deletes one at run time. The digest is the SHA-256 of the file's canonical
 * serialisation: object keys sorted recursively, no whitespace, array order as written.
 */
public class ConfiguredBalanceCriteria implements BalanceCriteria {

    private static final String AGGREGATION = "WEIGHTED_SUM_OF_SQUARED_DEVIATIONS";
    private static final String LOCATION = "classpath*:balance-criteria/v*.json";

    private final List<BalanceCriterion> criteria;

    public ConfiguredBalanceCriteria(ObjectMapper objectMapper) {
        Resource[] resources;
        try {
            resources = new PathMatchingResourcePatternResolver().getResources(LOCATION);
        } catch (IOException e) {
            throw new UncheckedIOException("Balance criteria could not be listed", e);
        }
        var parsed = Arrays.stream(resources)
                .map(resource -> read(objectMapper, resource))
                .sorted(Comparator.comparingInt(BalanceCriterion::version))
                .toList();
        if (parsed.stream().map(BalanceCriterion::version).distinct().count() != parsed.size()) {
            throw new IllegalStateException("A balance criterion version is configured twice");
        }
        this.criteria = parsed;
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

    /**
     * The tables a document declares. A document that declares none, as version 1 does, plays one uniform table per
     * policy its lines name, called by the policy reference and played in both phases.
     */
    private static List<CriterionTable> tables(JsonNode document) {
        var declared = document.get("tables");
        if (declared != null && !declared.isNull()) {
            return StreamSupport.stream(declared.spliterator(), false)
                    .map(node -> new CriterionTable(
                            node.get("id").asString(),
                            strings(node.get("seats")),
                            strings(node.get("phases")).stream()
                                    .map(CriterionPhase::valueOf)
                                    .collect(Collectors.toSet())))
                    .toList();
        }
        var policies = new LinkedHashSet<String>();
        document.get("lines").forEach(line -> policies.addAll(strings(line.get("policies"))));
        return policies.stream()
                .map(policy ->
                        new CriterionTable(policy, Collections.nCopies(5, policy), Set.of(CriterionPhase.values())))
                .toList();
    }

    /** The populations a line declares, or one population per policy it names, on the table called by the policy. */
    private static List<CriterionPopulation> populations(JsonNode node) {
        var declared = node.get("populations");
        if (declared != null && !declared.isNull()) {
            return StreamSupport.stream(declared.spliterator(), false)
                    .map(population -> new CriterionPopulation(
                            population.get("table").asString(),
                            population.get("policy").asString()))
                    .toList();
        }
        return strings(node.get("policies")).stream()
                .map(policy -> new CriterionPopulation(policy, policy))
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
