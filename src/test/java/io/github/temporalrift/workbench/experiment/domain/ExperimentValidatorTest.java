package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyReferenceVerifierAdapter;
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;
import io.github.temporalrift.workbench.policy.domain.baseline.FactionSearchBundle;
import io.github.temporalrift.workbench.policy.domain.baseline.SearchParameters;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;

class ExperimentValidatorTest {

    @Test
    void validManifestPasses() {
        var view = ExperimentValidator.validate(ExperimentManifests.valid());

        assertThat(view.name()).isEqualTo("threshold experiment");
        assertThat(view.variantLabels()).containsExactly("baseline", "candidate");
        assertThat(view.seeds()).containsExactly("42");
    }

    @Test
    void duplicateFactionInsideOneSetIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var sets = manifest.withArray("factionSets");
        var dupe = sets.arrayNode();
        dupe.add("ERASERS");
        dupe.add("ERASERS");
        dupe.add("WEAVERS");
        sets.add(dupe);

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void unknownFactionIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var sets = manifest.withArray("factionSets");
        var polluted = sets.arrayNode();
        polluted.add("ERASERS");
        polluted.add("PROPHETS");
        polluted.add("TIME_LORDS");
        sets.add(polluted);

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void factionSetSizeOutsideSelectedCountsIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("playerCounts").removeAll();

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void seedAboveUint64MaxIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var seeds = manifest.withArray("seeds");
        seeds.removeAll();
        seeds.add("18446744073709551616");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void aRefusalNamesTheOffendingValue() {
        var duplicateSeed = (ObjectNode) ExperimentManifests.valid().deepCopy();
        duplicateSeed.withArray("seeds").add("42");
        var badSeat = (ObjectNode) ExperimentManifests.valid().deepCopy();
        badSeat.withArray("tables").get(0).withArray("seats").get(0).asObject().put("seatIndex", -1);
        var badDigest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        badDigest.withArray("variants").get(1).asObject().put("effectiveContentDigest", "nope");

        var duplicateFaction = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var sets = duplicateFaction.withArray("factionSets");
        var expectedIndex = sets.size();
        var repeated = sets.addArray();
        repeated.add("WEAVERS");
        repeated.add("WEAVERS");
        repeated.add("WEAVERS");

        assertThat(fieldOf(duplicateFaction)).isEqualTo("factionSets[" + expectedIndex + "]");
        var emptySeatVersion = (ObjectNode) ExperimentManifests.valid().deepCopy();
        emptySeatVersion
                .withArray("tables")
                .get(0)
                .withArray("seats")
                .get(0)
                .asObject()
                .put("policyVersion", "");

        assertThat(fieldOf(emptySeatVersion)).isEqualTo("tables[0].seats[0].policyId");
        assertThat(fieldOf(duplicateSeed)).isEqualTo("seeds[1]");
        assertThat(fieldOf(badSeat)).isEqualTo("tables[0].seats[0]");
        assertThat(fieldOf(badDigest)).isEqualTo("variants[1].effectiveContentDigest");
    }

    private static String fieldOf(ObjectNode manifest) {
        try {
            ExperimentValidator.validate(manifest);
        } catch (ExperimentValidationException ex) {
            return ex.field().orElseThrow();
        }
        throw new AssertionError("the manifest was accepted");
    }

    @Test
    void aHundredVariantsAreAcceptedWhenTheMatrixStaysUnderTheCaseBound() {
        var manifest = (ObjectNode) ExperimentManifests.threePlayerSingleSet().deepCopy();
        var variants = manifest.putArray("variants");
        for (var index = 0; index < 100; index++) {
            variants.add(ExperimentManifests.variant("c-%03d".formatted(index), "a".repeat(64), "b".repeat(64)));
        }

        assertThat(ExperimentValidator.validate(manifest).variantLabels()).hasSize(100);
    }

    @Test
    void theCaseBoundRefusesAWaveThatWouldExpandPastIt() {
        var manifest = (ObjectNode) ExperimentManifests.threePlayerSingleSet().deepCopy();
        var variants = manifest.putArray("variants");
        for (var index = 0; index < 100; index++) {
            variants.add(ExperimentManifests.variant("c-%03d".formatted(index), "a".repeat(64), "b".repeat(64)));
        }
        var seeds = manifest.putArray("seeds");
        for (var seed = 1; seed <= 400; seed++) {
            seeds.add(Integer.toString(seed));
        }

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .hasMessageContaining("at most 100000");
    }

    @Test
    void duplicateVariantLabelsAreInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("variants").get(1).asObject().put("label", "baseline");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void malformedArtifactDigestIsManifestMismatch() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("variants").get(0).asObject().put("effectiveRulesDigest", "not-a-digest");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.MANIFEST_MISMATCH);
    }

    @Test
    void malformedServiceProvenanceIsManifestMismatch() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withObject("services").withObject("gameService").put("imageDigest", "latest");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.MANIFEST_MISMATCH);
    }

    @Test
    void credentialCarryingManifestIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("policies")
                .get(0)
                .asObject()
                .withObject("parameters")
                .put("api_password", "hunter2");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void machineLocalPathInManifestIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("policies")
                .get(0)
                .asObject()
                .withObject("parameters")
                .put("snapshot", "file:///home/designer/snapshot.json");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void ordinaryWordsContainingSecretMarkersAreAllowed() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.put("name", "secret-hitler variant");
        manifest.withArray("policies")
                .get(0)
                .asObject()
                .withObject("parameters")
                .put("tokenizer", "risk-averse");

        assertThat(ExperimentValidator.validate(manifest).name()).isEqualTo("secret-hitler variant");
    }

    @Test
    void aVariantMayOverrideAParameterWhoseKeyNamesASecretOutcome() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var variant = manifest.withArray("variants").get(1).asObject();
        variant.withObject("overrides").put("game.rules.scoring.score-deltas.SECRET_OUTCOME_WON", 5);
        variant.put("parameterSpaceVersion", 1);

        assertThat(ExperimentValidator.validate(manifest).name()).isEqualTo("threshold experiment");
    }

    @Test
    void aSecretNamedFieldOfAVariantIsStillRejected() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("variants").get(1).asObject().put("password", "hunter2");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .hasMessageContaining("credentials or secrets");
    }

    private static final PolicyReferenceVerifier VERIFIER =
            new PolicyReferenceVerifierAdapter(new BaselinePolicyCatalog());

    private static ObjectNode withFirstPolicy(java.util.function.Consumer<ObjectNode> edit) {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        edit.accept(manifest.withArray("policies").get(0).asObject());
        return manifest;
    }

    @Test
    void knownBaselinePoliciesResolve() {
        assertThat(ExperimentValidator.validate(ExperimentManifests.valid(), VERIFIER)
                        .policies())
                .hasSize(2);
    }

    @Test
    void unknownPolicyIsInvalid() {
        var manifest = withFirstPolicy(policy -> policy.put("id", "heuristic"));

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest, VERIFIER))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void policyDigestMismatchIsAManifestMismatch() {
        var manifest = withFirstPolicy(policy -> policy.put("artifactDigest", "f".repeat(64)));

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest, VERIFIER))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.MANIFEST_MISMATCH);
    }

    @Test
    void baselinePolicyWithParametersIsInvalid() {
        var manifest = withFirstPolicy(policy -> policy.withObject("parameters").put("epsilon", 1));

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest, VERIFIER))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void aMatrixLargerThanTheCapIsInvalid() {
        // 55 coordinates per seed, variant and policy: 1,819 seeds exceed the 100,000-case cap.
        var manifest = (ObjectNode)
                ExperimentManifests.singleSeedSinglePolicySingleVariant().deepCopy();
        var seeds = manifest.putArray("seeds");
        for (var seed = 1; seed <= 1_819; seed++) {
            seeds.add(String.valueOf(seed));
        }

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .hasMessageContaining("expands to 100045 cases");
    }

    @Test
    void aMatrixExactlyAtTheCapIsValid() {
        var manifest =
                (ObjectNode) ExperimentManifests.fourPlayerSeeds(5_000, 4).deepCopy();

        assertThat(ExperimentValidator.validate(manifest).seeds()).hasSize(5_000);
    }

    @Test
    void executionBoundsAboveTheirCeilingsAreInvalid() {
        for (var field : new String[] {"concurrency", "caseWallTimeoutSeconds", "maxRejectedCandidatesPerWindow"}) {
            var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
            manifest.put(field, Integer.MAX_VALUE);

            assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                    .as(field)
                    .isInstanceOf(ExperimentValidationException.class)
                    .hasMessageContaining(field + " must be an integer from 1 to");
        }
    }

    private static ObjectNode searchEntry(java.util.Map<String, Integer> parameters, String digest) {
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var entry = mapper.createObjectNode();
        entry.put("id", "faction-search");
        entry.put("version", "1.0.0");
        entry.put("artifactDigest", digest);
        var values = entry.putObject("parameters");
        parameters.forEach(values::put);
        return entry;
    }

    private static ObjectNode withSearchTable(ObjectNode first, ObjectNode second, boolean namesDigests) {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var policies = manifest.putArray("policies");
        policies.add(ExperimentManifests.valid().get("policies").get(1));
        policies.add(first);
        policies.add(second);
        var tables = manifest.putArray("tables");
        var table = tables.addObject();
        table.put("id", "hunt");
        var seats = table.putArray("seats");
        for (var seat = 0; seat < 5; seat++) {
            var entry = seats.addObject();
            entry.put("seatIndex", seat);
            var policy = seat == 0 ? first : seat == 1 ? second : policies.get(0);
            entry.put("policyId", policy.get("id").asString());
            entry.put("policyVersion", policy.get("version").asString());
            if (namesDigests) {
                entry.put("policyDigest", policy.get("artifactDigest").asString());
            }
        }
        return manifest;
    }

    private static final String SCAN = SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN);
    private static final String TRACE = SearchParameters.cardKey(Faction.WEAVERS, CardType.TRACE);

    @Test
    void twoParameterSetsOfOneBundleAreTwoPolicies() {
        var first = searchEntry(
                java.util.Map.of(SCAN, 8),
                FactionSearchBundle.of(java.util.Map.of(SCAN, 8)).artifactDigest());
        var second = searchEntry(
                java.util.Map.of(TRACE, 7),
                FactionSearchBundle.of(java.util.Map.of(TRACE, 7)).artifactDigest());

        var view = ExperimentValidator.validate(withSearchTable(first, second, true), VERIFIER);

        assertThat(view.policies()).hasSize(3);
        assertThat(view.tables().get(0).seats().get(0))
                .isNotEqualTo(view.tables().get(0).seats().get(1));
    }

    @Test
    void aSeatThatNamesNoDigestForSharedIdAndVersionIsInvalid() {
        var first = searchEntry(
                java.util.Map.of(SCAN, 8),
                FactionSearchBundle.of(java.util.Map.of(SCAN, 8)).artifactDigest());
        var second = searchEntry(
                java.util.Map.of(TRACE, 7),
                FactionSearchBundle.of(java.util.Map.of(TRACE, 7)).artifactDigest());
        var manifest = withSearchTable(first, second, false);

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest, VERIFIER))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void anUnknownSearchParameterIsInvalidAndAStaleDigestIsAMismatch() {
        var digest = FactionSearchBundle.defaults().artifactDigest();
        var unknown = withSearchTable(
                searchEntry(java.util.Map.of("nope", 1), digest),
                searchEntry(
                        java.util.Map.of(TRACE, 7),
                        FactionSearchBundle.of(java.util.Map.of(TRACE, 7)).artifactDigest()),
                true);
        var stale = withSearchTable(
                searchEntry(java.util.Map.of(SCAN, 8), digest),
                searchEntry(
                        java.util.Map.of(TRACE, 7),
                        FactionSearchBundle.of(java.util.Map.of(TRACE, 7)).artifactDigest()),
                true);

        assertThatThrownBy(() -> ExperimentValidator.validate(unknown, VERIFIER))
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
        assertThatThrownBy(() -> ExperimentValidator.validate(stale, VERIFIER))
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.MANIFEST_MISMATCH);
    }
}
