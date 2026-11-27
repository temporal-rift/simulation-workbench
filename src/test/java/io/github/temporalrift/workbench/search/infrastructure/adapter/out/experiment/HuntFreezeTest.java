package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;
import io.github.temporalrift.workbench.policy.domain.baseline.FactionSearchBundle;
import io.github.temporalrift.workbench.policy.domain.baseline.SearchParameters;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignKind;
import io.github.temporalrift.workbench.search.domain.campaign.HuntPlan;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ParameterGrid;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** What a hunt freezes: a table per candidate, every seat playing it, over the sets that hold the faction. */
class HuntFreezeTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SCAN = SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN);
    private static final String TRACE = SearchParameters.cardKey(Faction.WEAVERS, CardType.TRACE);

    private final ExperimentFactory experiments = mock(ExperimentFactory.class);
    private final LaneEnvironments lanes = mock(LaneEnvironments.class);
    private final CampaignExperimentsAdapter adapter = new CampaignExperimentsAdapter(
            experiments, new BaselinePolicyCatalog(), lanes, MAPPER, new CampaignExperimentsAdapter.Bounds(3600, 10));

    @BeforeEach
    void theSimulatorIsEnabledAndTheBaselineRulesPreview() {
        when(lanes.simulator()).thenReturn(Optional.of(bundle()));
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());
        when(experiments.previewVariant(anyString(), anyInt(), anyMap()))
                .thenReturn(new ExperimentFactory.VariantPreview("e".repeat(64), new TreeMap<>()));
    }

    @Test
    void theGridHoldsOnlyTheSearchedSubsetOfTheHuntedFactionEachAtItsDefault() {
        var grid = adapter.grid(definition(Faction.WEAVERS, 5));

        assertThat(grid.axes()).hasSizeBetween(12, 16);
        assertThat(grid.axes()).extracting(ParameterGrid.Axis::key).contains(SCAN, TRACE);
        assertThat(grid.axes())
                .extracting(ParameterGrid.Axis::key)
                .noneMatch(key -> key.contains("SEAL") || key.contains("PROPHETS") || key.contains("ERASERS"));
        assertThat(grid.baselinePoint()).hasSize(grid.axes().size());
        grid.axes().forEach(axis -> assertThat(axis.baselineIndex()).isBetween(0, axis.highestIndex()));
    }

    @Test
    void aCandidateIsWhatItsParametersMakeSoTheSameSetIsTheSameCandidate() {
        var validation = adapter.validation(definition(Faction.WEAVERS, 5));
        var scanHigh = new TreeMap<String, ParameterValue>();
        scanHigh.put(SCAN, ParameterValue.of(BigDecimal.valueOf(8)));
        var twice = new TreeMap<String, ParameterValue>();
        twice.put(SCAN, ParameterValue.of(BigDecimal.valueOf(8)));
        var other = new TreeMap<String, ParameterValue>();
        other.put(TRACE, ParameterValue.of(BigDecimal.valueOf(7)));

        var first = validation.validate(scanHigh).orElseThrow();
        var second = validation.validate(twice).orElseThrow();
        var different = validation.validate(other).orElseThrow();

        assertThat(first.effectiveRulesDigest()).isEqualTo(second.effectiveRulesDigest());
        assertThat(first.effectiveRulesDigest()).isNotEqualTo(different.effectiveRulesDigest());
        assertThat(first.effectiveRulesDigest())
                .isEqualTo(FactionSearchBundle.of(java.util.Map.of(SCAN, 8)).artifactDigest());
    }

    @Test
    void aParameterAtItsDefaultIsDroppedAndTheDefaultsAreTheDefaultBundle() {
        var validation = adapter.validation(definition(Faction.WEAVERS, 5));
        var defaults = new TreeMap<String, ParameterValue>();
        var spec = SearchParameters.spec(SCAN).orElseThrow();
        defaults.put(SCAN, ParameterValue.of(BigDecimal.valueOf(spec.defaultValue())));

        var validated = validation.validate(defaults).orElseThrow();

        assertThat(validated.overrides()).isEmpty();
        assertThat(validated.effectiveRulesDigest())
                .isEqualTo(FactionSearchBundle.defaults().artifactDigest());
    }

    @Test
    void aWaveHasOneTablePerCandidateEverySeatPlayingItOverTheSetsThatHoldTheFaction() {
        var one = candidate("c-0001", SCAN, 8);
        var two = candidate("c-0002", TRACE, 7);

        adapter.freeze(
                UUID.randomUUID(),
                definition(Faction.WEAVERS, 5),
                0,
                List.of(one, two),
                List.of("11", "12"),
                List.of());

        var manifest = frozen();
        assertThat(manifest.path("policies")).hasSize(2);
        assertThat(manifest.path("policies")
                        .valueStream()
                        .map(policy -> policy.path("id").asString()))
                .containsOnly("faction-search");
        assertThat(manifest.path("policies")
                        .get(0)
                        .path("parameters")
                        .path(SCAN)
                        .asInt())
                .isEqualTo(8);
        var tables = manifest.path("tables");
        assertThat(tables.valueStream().map(table -> table.path("id").asString()))
                .containsExactly("c-0001", "c-0002");
        var seats = tables.get(0).path("seats");
        assertThat(seats).hasSize(5);
        seats.forEach(seat -> {
            assertThat(seat.path("policyId").asString()).isEqualTo("faction-search");
            assertThat(seat.path("policyDigest").asString()).isEqualTo(one.effectiveRulesDigest());
        });
        assertThat(manifest.path("factionSets")).hasSize(1);
        assertThat(manifest.path("variants")).hasSize(1);
        assertThat(manifest.path("seatRotationMode").asString()).isEqualTo("CYCLIC");
        assertThat(manifest.path("playerCounts").valueStream().map(JsonNode::asInt))
                .containsExactly(5);
    }

    @Test
    void atFourPlayersOnlyTheSetsHoldingTheFactionArePlayed() {
        adapter.freeze(
                UUID.randomUUID(),
                definition(Faction.WEAVERS, 4),
                0,
                List.of(candidate("c-0001", SCAN, 8)),
                List.of("11"),
                List.of());

        var sets = frozen().path("factionSets");

        assertThat(sets).hasSize(4);
        sets.forEach(
                set -> assertThat(set.valueStream().map(JsonNode::asString)).contains("WEAVERS"));
        assertThat(adapter.casesPerGame(definition(Faction.WEAVERS, 4), List.of()))
                .isEqualTo(HuntPlan.casesPerSeed(4))
                .isEqualTo(16);
    }

    @Test
    void aConfirmationPlaysTheCandidateOnOneTableAndTheStandardSeatOnTheOther() {
        var best = candidate("c-0007", SCAN, 8);
        var definition = definition(Faction.WEAVERS, 5);
        when(experiments.freeze(any(), any())).thenReturn(UUID.randomUUID());

        // the standard bundle comes from the catalog, whose listing the policy module serves
        adapter.freezeHuntConfirmation(UUID.randomUUID(), definition, best, List.of("21", "22"));

        var manifest = frozen();
        assertThat(manifest.path("tables")
                        .valueStream()
                        .map(table -> table.path("id").asString()))
                .containsExactly(HuntPlan.CANDIDATE_TABLE, HuntPlan.STANDARD_TABLE);
        assertThat(manifest.path("tables")
                        .get(0)
                        .path("seats")
                        .get(0)
                        .path("policyId")
                        .asString())
                .isEqualTo("faction-search");
        assertThat(manifest.path("tables")
                        .get(1)
                        .path("seats")
                        .get(4)
                        .path("policyId")
                        .asString())
                .isEqualTo("faction-spread");
        assertThat(manifest.path("policies")).hasSize(2);
        assertThat(manifest.path("seeds").valueStream().map(JsonNode::asString)).containsExactly("21", "22");
    }

    @Test
    void theFrozenWaveIsAManifestTheExperimentValidatorAccepts() {
        adapter.freeze(
                UUID.randomUUID(),
                definition(Faction.WEAVERS, 5),
                0,
                List.of(candidate("c-0001", SCAN, 8), candidate("c-0002", TRACE, 7)),
                List.of("11", "12"),
                List.of());

        var view = io.github.temporalrift.workbench.experiment.domain.ExperimentValidator.validate(
                frozen(),
                new io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy
                        .PolicyReferenceVerifierAdapter(new BaselinePolicyCatalog()));

        assertThat(view.tables()).hasSize(2);
        assertThat(view.tables().get(0).seats())
                .doesNotContainAnyElementsOf(view.tables().get(1).seats());
        assertThat(view.tables().get(0).uniform()).isTrue();
    }

    private JsonNode frozen() {
        var manifest = ArgumentCaptor.forClass(JsonNode.class);
        verify(experiments).freeze(any(), manifest.capture());
        return manifest.getValue();
    }

    private static Candidate candidate(String id, String key, int value) {
        var overrides = new TreeMap<String, ParameterValue>();
        overrides.put(key, ParameterValue.of(BigDecimal.valueOf(value)));
        return new Candidate(
                id,
                0,
                List.of(0),
                overrides,
                FactionSearchBundle.of(java.util.Map.of(key, value)).artifactDigest(),
                0);
    }

    private static CampaignDefinition definition(Faction faction, int players) {
        var baseline = MAPPER.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        baseline.putObject("contentArtifact").put("digest", "c".repeat(64));
        return new CampaignDefinition(
                "hunt",
                baseline,
                1,
                1,
                MAPPER.createArrayNode(),
                List.of(players),
                null,
                "7",
                "9",
                HuntPlan.algorithm(players),
                CampaignKind.POLICY_HUNT,
                faction,
                1);
    }

    private static LaneBundle bundle() {
        return new LaneBundle(
                new LaneBundle.Artifact("d".repeat(64), "rules"),
                new LaneBundle.Artifact("c".repeat(64), "content"),
                "d".repeat(64),
                "c".repeat(64),
                new LaneBundle.ServiceBuild("sha256:" + "a".repeat(64), "a".repeat(40)),
                new LaneBundle.ServiceBuild("sha256:" + "b".repeat(64), "b".repeat(40)),
                new LaneBundle.ServiceBuild("sha256:" + "c".repeat(64), "c".repeat(40)),
                new TreeMap<>(java.util.Map.of("simulation-control-api", "2.3.0")),
                1);
    }
}
