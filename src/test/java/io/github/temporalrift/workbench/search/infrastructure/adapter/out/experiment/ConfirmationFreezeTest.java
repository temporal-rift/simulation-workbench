package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
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
import io.github.temporalrift.workbench.search.domain.campaign.PolicyNotAvailableException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.campaign.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;

/** The experiment a confirmation freezes: one candidate, the seeds given, and the builds of the simulator. */
class ConfirmationFreezeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final UUID KEY = UUID.randomUUID();

    private final ExperimentFactory experiments = mock(ExperimentFactory.class);
    private final LaneEnvironments lanes = mock(LaneEnvironments.class);
    private final PolicyCatalog catalog = mock(PolicyCatalog.class);
    private final CampaignExperimentsAdapter adapter = new CampaignExperimentsAdapter(
            experiments, catalog, lanes, MAPPER, new CampaignExperimentsAdapter.Bounds(3600, 10));
    private static final PolicyRef GREEDY = new PolicyRef("faction-greedy", "1");
    private static final PolicyRef RANDOM = new PolicyRef("random", "1");
    private static final List<SeatTable> CRITERION_TABLES = List.of(
            new SeatTable("faction-greedy-v1", Collections.nCopies(5, GREEDY)),
            new SeatTable("random-v1", Collections.nCopies(5, RANDOM)),
            new SeatTable("random-seat-vs-faction-greedy-v1", List.of(RANDOM, GREEDY, GREEDY, GREEDY, GREEDY)));

    @BeforeEach
    void theCatalogServesTheCriterionPoliciesAndTheSimulatorIsEnabled() {
        when(catalog.list())
                .thenReturn(List.of(
                        new PolicyDescriptor("faction-greedy", "1.0.0", "a".repeat(64), "greedy"),
                        new PolicyDescriptor("random", "1.10.0", "e".repeat(64), "latest"),
                        new PolicyDescriptor("random", "1.2.0", "b".repeat(64), "random"),
                        new PolicyDescriptor("random", "2.0.0", "c".repeat(64), "newer")));
        when(lanes.simulator()).thenReturn(Optional.of(bundle("simulator")));
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());
    }

    @Test
    void theManifestPlaysEveryPolicyTheCriterionNamesWhateverThePoliciesOfTheCampaign() {
        var node = frozen(candidate("c-0000", new TreeMap<>()));

        var policies = node.path("policies");
        assertThat(policies).hasSize(2);
        assertThat(policies.get(0).path("id").asString()).isEqualTo("faction-greedy");
        assertThat(policies.get(1).path("version").asString()).isEqualTo("1.10.0");
        var tables = node.path("tables");
        assertThat(tables).hasSize(3);
        assertThat(tables.get(1).path("seats")).hasSize(5);
    }

    @Test
    void theManifestPlaysTheCriterionsConfirmationTablesSeatBySeat() {
        var tables = frozen(candidate("c-0000", new TreeMap<>())).path("tables");

        assertThat(tables.valueStream()
                        .map(table -> table.path("id").asString())
                        .toList())
                .containsExactly("faction-greedy-v1", "random-v1", "random-seat-vs-faction-greedy-v1");
        var mixed = tables.get(2).path("seats");
        assertThat(mixed.get(0).path("policyId").asString()).isEqualTo("random");
        assertThat(mixed.get(0).path("policyVersion").asString()).isEqualTo("1.10.0");
        for (var seat = 1; seat < 5; seat++) {
            assertThat(mixed.get(seat).path("seatIndex").asInt()).isEqualTo(seat);
            assertThat(mixed.get(seat).path("policyId").asString()).isEqualTo("faction-greedy");
        }
    }

    @Test
    void aRebuiltSimulatorGetsAnExperimentKeyOfItsOwn() {
        var candidate = candidate("c-0000", new TreeMap<>());
        adapter.freezeConfirmation(KEY, definition(), candidate, List.of("11"), CRITERION_TABLES);
        when(lanes.simulator()).thenReturn(Optional.of(bundle("rebuilt")));
        adapter.freezeConfirmation(KEY, definition(), candidate, List.of("11"), CRITERION_TABLES);

        var keys = ArgumentCaptor.forClass(UUID.class);
        verify(experiments, times(2)).freeze(keys.capture(), any());
        assertThat(keys.getAllValues()).doesNotHaveDuplicates();
    }

    @Test
    void theSameBuildsKeepTheSameExperimentKey() {
        var candidate = candidate("c-0000", new TreeMap<>());
        adapter.freezeConfirmation(KEY, definition(), candidate, List.of("11"), CRITERION_TABLES);
        adapter.freezeConfirmation(KEY, definition(), candidate, List.of("11"), CRITERION_TABLES);

        var keys = ArgumentCaptor.forClass(UUID.class);
        verify(experiments, times(2)).freeze(keys.capture(), any());
        assertThat(keys.getAllValues().get(0)).isEqualTo(keys.getAllValues().get(1));
    }

    @Test
    void aCriterionPolicyNoBundleServesIsRefusedBeforeAnythingIsFrozen() {
        var definition = definition();
        var candidate = candidate("c-0000", new TreeMap<>());
        var missing = List.of(new SeatTable("missing-v1", Collections.nCopies(5, new PolicyRef("missing", "1"))));
        var seeds = List.of("11");
        assertThatThrownBy(() -> adapter.freezeConfirmation(KEY, definition, candidate, seeds, missing))
                .isInstanceOf(PolicyNotAvailableException.class);

        verifyNoInteractions(experiments);
    }

    @Test
    void theManifestNamesTheBuildsOfTheSimulatorAndTheCandidatesOverrides() {
        var overrides = new TreeMap<String, BigDecimal>();
        overrides.put("game.rules.a", BigDecimal.TEN);

        var node = frozen(candidate("c-0001", overrides));

        assertThat(node.path("services")
                        .path("gameService")
                        .path("sourceRevision")
                        .asString())
                .isEqualTo("game-simulator");
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
        assertThat(node.path("playerCounts").valueStream().map(JsonNode::asInt).toList())
                .containsExactly(3, 4, 5);
        assertThat(node.path("seatRotationMode").asString()).isEqualTo("CYCLIC");
    }

    @Test
    void aDisabledSimulatorFreezesNothing() {
        when(lanes.simulator()).thenReturn(Optional.empty());

        var definition = definition();
        var candidate = candidate("c-0000", new TreeMap<>());
        var seeds = List.of("11");
        assertThatThrownBy(() -> adapter.freezeConfirmation(KEY, definition, candidate, seeds, CRITERION_TABLES))
                .isInstanceOf(SimulatorNotAvailableException.class);

        verifyNoInteractions(experiments);
        assertThat(adapter.simulatorEnabled()).isFalse();
    }

    @Test
    void versionsAreOrderedByTheirNumbersAndMissingPartsCountAsZero() {
        assertThat(CampaignExperimentsAdapter.compareVersions("1.10.0", "1.2.0"))
                .isPositive();
        assertThat(CampaignExperimentsAdapter.compareVersions("1.2.0", "1.10.0"))
                .isNegative();
        assertThat(CampaignExperimentsAdapter.compareVersions("1.0", "1.0.0")).isZero();
        assertThat(CampaignExperimentsAdapter.compareVersions("1.0.1", "1.0")).isPositive();
    }

    private JsonNode frozen(Candidate candidate) {
        adapter.freezeConfirmation(KEY, definition(), candidate, List.of("11", "12", "13"), CRITERION_TABLES);
        var manifest = ArgumentCaptor.forClass(JsonNode.class);
        verify(experiments).freeze(any(), manifest.capture());
        return manifest.getValue();
    }

    private static Candidate candidate(String id, TreeMap<String, BigDecimal> overrides) {
        return new Candidate(id, 0, List.of(0), overrides, "e".repeat(64), 0);
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
                null,
                "7",
                "9",
                new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2, 100, 4, 2, 2));
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
