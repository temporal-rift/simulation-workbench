package io.github.temporalrift.workbench.experiment.domain.template;

import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyArtifacts;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;
import io.github.temporalrift.workbench.policy.domain.baseline.PolicyBundle;

class ExperimentTemplatesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TemplateBounds BOUNDS = new TemplateBounds(3600, 10);
    private static final PolicyArtifacts BASELINES =
            (id, version) -> baseline(id, version).map(PolicyBundle::artifactDigest);
    private static final PolicyReferenceVerifier VERIFIER = (id, version, digest, parameters) -> baseline(id, version)
            .map(bundle -> bundle.artifactDigest().equals(digest)
                    ? PolicyReferenceVerifier.Status.VALID
                    : PolicyReferenceVerifier.Status.DIGEST_MISMATCH)
            .orElse(PolicyReferenceVerifier.Status.UNKNOWN_POLICY);

    @Test
    void theTwoTemplatesAreOfferedInTemplateIdOrder() {
        var templates = ExperimentTemplates.builtIn(3, BOUNDS, BASELINES);

        assertThat(templates)
                .extracting(ExperimentTemplate::templateId, ExperimentTemplate::variantCount)
                .containsExactly(tuple("baseline-policy-comparison", 1), tuple("rules-variant-comparison", 2));
    }

    @Test
    void bothTemplatesShareTheDefaultMatrixAndBounds() {
        for (var template : ExperimentTemplates.builtIn(3, BOUNDS, BASELINES)) {
            var defaults = template.defaults();

            assertThat(defaults.seeds()).containsExactly("42");
            assertThat(defaults.playerCounts()).containsExactly(3, 4, 5);
            assertThat(defaults.factionSets()).hasSize(16);
            assertThat(defaults.factionSets().stream().collect(groupingBy(List::size, counting())))
                    .containsEntry(3, 10L)
                    .containsEntry(4, 5L)
                    .containsEntry(5, 1L);
            assertThat(defaults.factionSets()).doesNotHaveDuplicates();
            assertThat(defaults.seatRotationMode()).isEqualTo("CYCLIC");
            assertThat(defaults.timingMode()).isEqualTo("LOGICAL");
            assertThat(defaults.concurrency()).isEqualTo(3);
            assertThat(defaults.caseWallTimeoutSeconds()).isEqualTo(3600);
            assertThat(defaults.maxRejectedCandidatesPerWindow()).isEqualTo(10);
        }
    }

    @Test
    void eachTemplatePlaysItsBaselinePoliciesOnEverySeat() {
        var templates = ExperimentTemplates.builtIn(1, BOUNDS, BASELINES);

        assertThat(templates.getFirst().defaults().policies())
                .containsExactly(
                        new ExperimentTemplate.Policy(
                                "random", "1.0.0", BaselinePolicies.RANDOM_V1.artifactDigest(), List.of(0, 1, 2, 3, 4)),
                        new ExperimentTemplate.Policy(
                                "faction-greedy",
                                "1.0.0",
                                BaselinePolicies.FACTION_GREEDY_V1.artifactDigest(),
                                List.of(0, 1, 2, 3, 4)));
        assertThat(templates.getLast().defaults().policies())
                .extracting(ExperimentTemplate.Policy::id)
                .containsExactly("random");
    }

    @Test
    void concurrencyFollowsTheLanesWithinTheManifestBound() {
        assertThat(ExperimentTemplates.builtIn(0, BOUNDS, BASELINES)
                        .getFirst()
                        .defaults()
                        .concurrency())
                .isEqualTo(1);
        assertThat(ExperimentTemplates.builtIn(65, BOUNDS, BASELINES)
                        .getFirst()
                        .defaults()
                        .concurrency())
                .isEqualTo(64);
    }

    @Test
    void aManifestAssembledFromEitherTemplateIsAccepted() {
        for (var template : ExperimentTemplates.builtIn(2, BOUNDS, BASELINES)) {
            var view = ExperimentValidator.validate(assembled(template), VERIFIER);

            assertThat(view.factionSets()).hasSize(16);
        }
    }

    @Test
    void boundsAManifestWouldRefuseStopStartup() {
        assertThatThrownBy(() -> new TemplateBounds(0, 10))
                .hasMessage("workbench.experiment.templates.case-wall-timeout-seconds must be from 1 to 86400: 0");
        assertThatThrownBy(() -> new TemplateBounds(3600, 1001))
                .hasMessage("workbench.experiment.templates.max-rejected-candidates-per-window"
                        + " must be from 1 to 1000: 1001");
    }

    /** The template's defaults over a manifest whose bundles, services and contracts are already chosen. */
    private static ObjectNode assembled(ExperimentTemplate template) {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var defaults = template.defaults();
        manifest.set("seeds", MAPPER.valueToTree(defaults.seeds()));
        manifest.set("playerCounts", MAPPER.valueToTree(defaults.playerCounts()));
        manifest.set("factionSets", MAPPER.valueToTree(defaults.factionSets()));
        manifest.put("seatRotationMode", defaults.seatRotationMode());
        manifest.put("timingMode", defaults.timingMode());
        var policies = manifest.putArray("policies");
        defaults.policies().forEach(policy -> {
            var node = policies.addObject();
            node.put("id", policy.id());
            node.put("version", policy.version());
            node.put("artifactDigest", policy.artifactDigest());
            node.putObject("parameters");
            var seats = node.putArray("seatAssignment");
            policy.seats()
                    .forEach(seat -> seats.addObject()
                            .put("seatIndex", seat)
                            .put("policyId", policy.id())
                            .put("policyVersion", policy.version()));
        });
        manifest.put("concurrency", defaults.concurrency());
        manifest.put("caseWallTimeoutSeconds", defaults.caseWallTimeoutSeconds());
        manifest.put("maxRejectedCandidatesPerWindow", defaults.maxRejectedCandidatesPerWindow());
        if (template.variantCount() == 1) {
            var variants = manifest.withArray("variants");
            variants.remove(1);
        }
        return manifest;
    }

    private static Optional<PolicyBundle> baseline(String id, String version) {
        return Stream.of(BaselinePolicies.RANDOM_V1, BaselinePolicies.FACTION_GREEDY_V1)
                .filter(bundle -> bundle.id().equals(id) && bundle.version().equals(version))
                .findFirst();
    }
}
