package io.github.temporalrift.workbench.fidelity.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.application.query.PreviewMatrixQueryHandler;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyReferenceVerifierAdapter;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;
import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;

class FidelityManifestFactoryTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String IMAGE = "sha256:" + "1".repeat(64);

    private final EdgeVariants edge = ConfiguredEdgeVariants.load(MAPPER);
    private final FidelityManifestFactory factory =
            new FidelityManifestFactory(MAPPER, new FidelitySettings(3600, 10, 20, 64), 1);

    private static FidelityEnvironments.Simulator simulator(int slots) {
        var build = new FidelityEnvironment.ServiceBuild(IMAGE, "1111111");
        var environment = new FidelityEnvironment(
                build, build, build, "b".repeat(64), new TreeMap<>(Map.of("session-api", "3.0.0")));
        return new FidelityEnvironments.Simulator(
                environment,
                VariantRulesFixtures.ARTIFACT_DIGEST,
                "baseline-rules",
                "d".repeat(64),
                null,
                VariantRulesFixtures.baseline().digest(),
                slots);
    }

    private static FidelityVariant variant(VariantLabel label, Map<String, BigDecimal> overrides) {
        var preview = VariantRulesFixtures.variantRules().preview(VariantRulesFixtures.ARTIFACT_DIGEST, 1, overrides);
        return new FidelityVariant(label, preview.digest(), preview.overrides());
    }

    private List<FidelityVariant> variants() {
        return List.of(
                variant(VariantLabel.BASELINE, Map.of()),
                variant(VariantLabel.LOW, edge.low()),
                variant(VariantLabel.HIGH, edge.high()));
    }

    private static List<FidelityExperiments.Policy> policies() {
        var catalog = new BaselinePolicyCatalog();
        return catalog.list().stream()
                .map(policy -> new FidelityExperiments.Policy(policy.id(), policy.version(), policy.artifactDigest()))
                .toList();
    }

    @Test
    void theExperimentPlaysTheFullMatrixOfThreeVariantsAndTwoPolicies() {
        var manifest = factory.build("42", simulator(4), variants(), policies());

        var cases = new PreviewMatrixQueryHandler(new PolicyReferenceVerifierAdapter(new BaselinePolicyCatalog()))
                .handle(manifest);

        assertThat(cases).hasSize(330);
        assertThat(cases).extracting(c -> c.variantLabel()).containsOnly("baseline", "low", "high");
        assertThat(cases).extracting(c -> c.seed()).containsOnly("42");
        assertThat(cases).extracting(c -> c.playerCount()).containsOnly(3, 4, 5);
        assertThat(cases.stream().flatMap(c -> c.seats().stream()).map(seat -> seat.policyId()))
                .containsOnly("random", "faction-greedy");
    }

    @Test
    void theManifestFreezesWithTheDigestsTheVariantsMean() {
        var manifest = factory.build("42", simulator(4), variants(), policies());

        var frozen = VariantRulesFixtures.variantRules().freeze(manifest);

        assertThat(frozen.baselineDocuments()).isNotEmpty();
    }

    @Test
    void theBaselineVariantHasNoOverridesAndTheEdgesHaveOnlyTheirNormalisedOnes() {
        var manifest = factory.build("42", simulator(4), variants(), policies());

        var variantNodes = manifest.get("variants");
        assertThat(variantNodes.get(0).has("overrides")).isFalse();
        assertThat(variantNodes.get(1).get("overrides").size())
                .isEqualTo(variants().get(1).overrides().size());
        assertThat(variantNodes.get(1).get("parameterSpaceVersion").asInt()).isEqualTo(1);
    }

    @Test
    void theManifestPinsTheSimulatorsBuildsAndContracts() {
        var manifest = factory.build("42", simulator(4), variants(), policies());

        assertThat(manifest.get("services")
                        .get("gameService")
                        .get("sourceRevision")
                        .asText())
                .isEqualTo("1111111");
        assertThat(manifest.get("services")
                        .get("readService")
                        .get("imageDigest")
                        .asText())
                .isEqualTo(IMAGE);
        assertThat(manifest.get("contracts").get("session-api").asText()).isEqualTo("3.0.0");
    }

    @Test
    void concurrencyFollowsTheSimulatorsSlotsWithinTheBound() {
        assertThat(factory.build("42", simulator(4), variants(), policies())
                        .get("concurrency")
                        .asInt())
                .isEqualTo(4);
        assertThat(factory.build("42", simulator(500), variants(), policies())
                        .get("concurrency")
                        .asInt())
                .isEqualTo(64);
    }
}
