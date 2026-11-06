package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyDescriptor;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;

/** The experiment a confirmation freezes: one subject, the seeds given, and the builds of the real lane only. */
class ConfirmationFreezeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final UUID KEY = UUID.randomUUID();

    private final ExperimentFactory experiments = mock(ExperimentFactory.class);
    private final LaneEnvironments lanes = mock(LaneEnvironments.class);
    private final PolicyCatalog catalog = mock(PolicyCatalog.class);
    private final CampaignExperimentsAdapter adapter = new CampaignExperimentsAdapter(
            experiments, catalog, lanes, MAPPER, new CampaignExperimentsAdapter.Bounds(3600, 10));
    private static final List<PolicyRef> CRITERION_POLICIES =
            List.of(new PolicyRef("faction-greedy", "1"), new PolicyRef("random", "1"));

    @BeforeEach
    void theCatalogServesTheCriterionPolicies() {
        when(catalog.list())
                .thenReturn(List.of(
                        new PolicyDescriptor("faction-greedy", "1.0.0", "a".repeat(64), "greedy"),
                        new PolicyDescriptor("random", "1.10.0", "e".repeat(64), "latest"),
                        new PolicyDescriptor("random", "1.2.0", "b".repeat(64), "random"),
                        new PolicyDescriptor("random", "2.0.0", "c".repeat(64), "newer")));
    }

    @Test
    void theManifestPlaysEveryPolicyTheCriterionNamesWhateverThePoliciesOfTheCampaign() {
        when(lanes.equivalentDeployedLane(5))
                .thenReturn(Optional.of(new LaneEnvironments.DeployedLane("lane-1", bundle("deployed"))));
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());

        adapter.freezeConfirmation(
                KEY,
                definition(),
                new CampaignExperiments.Subject("baseline", new TreeMap<>(), "d".repeat(64)),
                List.of("11"),
                CRITERION_POLICIES);

        var manifest = ArgumentCaptor.forClass(JsonNode.class);
        verify(experiments).freeze(any(), manifest.capture());
        var policies = manifest.getValue().path("policies");
        assertThat(policies).hasSize(2);
        assertThat(policies.get(0).path("id").asString()).isEqualTo("faction-greedy");
        assertThat(policies.get(1).path("version").asString()).isEqualTo("1.10.0");
        assertThat(policies.get(1).path("seatAssignment")).hasSize(5);
    }

    @Test
    void aRedeployedLaneGetsAnExperimentKeyOfItsOwn() {
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());
        var subject = new CampaignExperiments.Subject("baseline", new TreeMap<>(), "d".repeat(64));
        when(lanes.equivalentDeployedLane(5))
                .thenReturn(Optional.of(new LaneEnvironments.DeployedLane("lane-1", bundle("deployed"))));
        adapter.freezeConfirmation(KEY, definition(), subject, List.of("11"), CRITERION_POLICIES);
        when(lanes.equivalentDeployedLane(5))
                .thenReturn(Optional.of(new LaneEnvironments.DeployedLane("lane-1", bundle("redeployed"))));
        adapter.freezeConfirmation(KEY, definition(), subject, List.of("11"), CRITERION_POLICIES);

        var keys = ArgumentCaptor.forClass(UUID.class);
        verify(experiments, org.mockito.Mockito.times(2)).freeze(keys.capture(), any());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void aCriterionPolicyNoBundleServesIsRefusedBeforeAnythingIsFrozen() {
        when(lanes.equivalentDeployedLane(5))
                .thenReturn(Optional.of(new LaneEnvironments.DeployedLane("lane-1", bundle("deployed"))));

        assertThatThrownBy(() -> adapter.freezeConfirmation(
                        KEY,
                        definition(),
                        new CampaignExperiments.Subject("baseline", new TreeMap<>(), "d".repeat(64)),
                        List.of("11"),
                        List.of(new PolicyRef("missing", "1"))))
                .isInstanceOf(CampaignPlayRefusedException.class);

        verifyNoInteractions(experiments);
    }

    @Test
    void theManifestNamesTheBuildsOfTheRealLaneAndNeverTheSimulators() {
        var simulator = bundle("simulator");
        var deployed = bundle("deployed");
        when(lanes.simulator()).thenReturn(Optional.of(simulator));
        when(lanes.equivalentDeployedLane(5))
                .thenReturn(Optional.of(new LaneEnvironments.DeployedLane("lane-1", deployed)));
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());
        var overrides = new TreeMap<String, BigDecimal>();
        overrides.put("game.rules.a", BigDecimal.TEN);

        adapter.freezeConfirmation(
                KEY,
                definition(),
                new CampaignExperiments.Subject("c-0001", overrides, "e".repeat(64)),
                List.of("11", "12", "13"),
                CRITERION_POLICIES);

        var manifest = ArgumentCaptor.forClass(JsonNode.class);
        verify(experiments).freeze(any(), manifest.capture());
        var node = manifest.getValue();
        assertThat(node.path("services")
                        .path("gameService")
                        .path("sourceRevision")
                        .asString())
                .isEqualTo("game-deployed");
        assertThat(node.toString()).doesNotContain("simulator");
        assertThat(node.path("variants")).hasSize(1);
        assertThat(node.path("variants").get(0).path("label").asString()).isEqualTo("c-0001");
        assertThat(node.path("variants")
                        .get(0)
                        .path("overrides")
                        .path("game.rules.a")
                        .decimalValue())
                .isEqualByComparingTo(BigDecimal.TEN);
        assertThat(node.path("seeds").valueStream().map(JsonNode::asString).toList())
                .containsExactly("11", "12", "13");
        assertThat(node.path("concurrency").asInt()).isEqualTo(1);
        assertThat(node.path("playerCounts").valueStream().map(JsonNode::asInt).toList())
                .containsExactly(3, 4, 5);
    }

    @Test
    void theBaselineSubjectCarriesNoOverrides() {
        when(lanes.equivalentDeployedLane(5))
                .thenReturn(Optional.of(new LaneEnvironments.DeployedLane("lane-1", bundle("deployed"))));
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());

        adapter.freezeConfirmation(
                KEY,
                definition(),
                new CampaignExperiments.Subject("baseline", new TreeMap<>(), "d".repeat(64)),
                List.of("11"),
                CRITERION_POLICIES);

        var manifest = ArgumentCaptor.forClass(JsonNode.class);
        verify(experiments).freeze(any(), manifest.capture());
        assertThat(manifest.getValue().path("variants").get(0).has("overrides")).isFalse();
    }

    @Test
    void withoutAnEquivalentDeployedLaneNothingIsFrozen() {
        when(lanes.equivalentDeployedLane(5)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.freezeConfirmation(
                        KEY,
                        definition(),
                        new CampaignExperiments.Subject("baseline", new TreeMap<>(), "d".repeat(64)),
                        List.of("11"),
                        CRITERION_POLICIES))
                .isInstanceOf(NoRealLaneException.class);

        verifyNoInteractions(experiments);
    }

    private static CampaignDefinition definition() {
        var baseline = MAPPER.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        baseline.putObject("contentArtifact").put("digest", "c".repeat(64));
        return new CampaignDefinition(
                "search",
                baseline,
                1,
                1,
                MAPPER.createArrayNode().add(MAPPER.createObjectNode().put("id", "random")),
                List.of(3, 4, 5),
                CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                "7",
                "9",
                new SearchAlgorithm(100, 4, 2, 2));
    }

    private static LaneBundle bundle(String name) {
        return new LaneBundle(
                new LaneBundle.Artifact("d".repeat(64), "rules"),
                new LaneBundle.Artifact("c".repeat(64), "content"),
                "d".repeat(64),
                "c".repeat(64),
                new LaneBundle.ServiceBuild("sha256:game-" + name, "game-" + name),
                new LaneBundle.ServiceBuild("sha256:timeline-" + name, "timeline-" + name),
                new LaneBundle.ServiceBuild("sha256:read-" + name, "read-" + name),
                new TreeMap<>(),
                1);
    }
}
