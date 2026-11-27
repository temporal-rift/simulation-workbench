package io.github.temporalrift.workbench.policy.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyReferenceVerdict;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;
import io.github.temporalrift.workbench.policy.domain.baseline.FactionSearchBundle;
import io.github.temporalrift.workbench.policy.domain.baseline.SearchParameters;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;

class BaselinePolicyCatalogTest {

    private final BaselinePolicyCatalog catalog = new BaselinePolicyCatalog();

    @Test
    void definedBundlesResolve() {
        for (var bundle : BaselinePolicies.all()) {
            assertThat(catalog.verify(bundle.id(), bundle.version(), bundle.artifactDigest(), 0))
                    .isEqualTo(PolicyReferenceVerdict.VALID);
        }
    }

    @Test
    void everyListedBundleResolvesAsAReference() {
        assertThat(catalog.list()).hasSize(4).allSatisfy(entry -> {
            assertThat(entry.description()).isNotBlank();
            assertThat(catalog.verify(entry.id(), entry.version(), entry.artifactDigest(), 0))
                    .isEqualTo(PolicyReferenceVerdict.VALID);
        });
    }

    @Test
    void unknownIdOrVersionIsUnknown() {
        var digest = BaselinePolicies.RANDOM.artifactDigest();

        assertThat(catalog.verify("heuristic", "1.0.0", digest, 0)).isEqualTo(PolicyReferenceVerdict.UNKNOWN_POLICY);
        assertThat(catalog.verify("random", "2.0.0", digest, 0)).isEqualTo(PolicyReferenceVerdict.UNKNOWN_POLICY);
    }

    @Test
    void wrongDigestMismatches() {
        assertThat(catalog.verify("faction-greedy", "1.0.0", "f".repeat(64), 0))
                .isEqualTo(PolicyReferenceVerdict.DIGEST_MISMATCH);
    }

    @Test
    void parametersAreUnsupported() {
        var bundle = BaselinePolicies.RANDOM;

        assertThat(catalog.verify(bundle.id(), bundle.version(), bundle.artifactDigest(), 1))
                .isEqualTo(PolicyReferenceVerdict.UNSUPPORTED_PARAMETERS);
    }

    @Test
    void aParameterizedEntryResolvesWhenItsDigestFollowsItsParameters() {
        var key = SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN);
        var digest = FactionSearchBundle.of(Map.of(key, 8)).artifactDigest();

        var verification = catalog.verify("faction-search", "1.0.0", digest, Map.<String, Object>of(key, 8L));

        assertThat(verification.verdict()).isEqualTo(PolicyReferenceVerdict.VALID);
    }

    @Test
    void aStaleDigestMismatches() {
        var key = SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN);

        var verification = catalog.verify(
                "faction-search",
                "1.0.0",
                FactionSearchBundle.defaults().artifactDigest(),
                Map.<String, Object>of(key, 8L));

        assertThat(verification.verdict()).isEqualTo(PolicyReferenceVerdict.DIGEST_MISMATCH);
    }

    @Test
    void anUnknownParameterIsNamed() {
        var verification = catalog.verify(
                "faction-search",
                "1.0.0",
                FactionSearchBundle.defaults().artifactDigest(),
                Map.<String, Object>of("nope", 1L));

        assertThat(verification.verdict()).isEqualTo(PolicyReferenceVerdict.UNKNOWN_PARAMETER);
        assertThat(verification.parameter()).isEqualTo("nope");
    }

    @Test
    void anOutOfRangeOffStepOrFractionalValueIsNamed() {
        var key = SearchParameters.scoped(SearchParameters.RUNNER_UP_PERCENT, Faction.PROPHETS);
        for (Object value : new Object[] {30L, 200L, "0.5"}) {
            var verification = catalog.verify(
                    "faction-search", "1.0.0", FactionSearchBundle.defaults().artifactDigest(), Map.of(key, value));

            assertThat(verification.verdict()).isEqualTo(PolicyReferenceVerdict.INVALID_PARAMETER_VALUE);
            assertThat(verification.parameter()).isEqualTo(key);
        }
    }

    @Test
    void aBaselineStillRefusesAnyParameter() {
        var bundle = BaselinePolicies.FACTION_SPREAD;

        assertThat(catalog.verify(
                                bundle.id(), bundle.version(), bundle.artifactDigest(), Map.<String, Object>of("x", 1L))
                        .verdict())
                .isEqualTo(PolicyReferenceVerdict.UNSUPPORTED_PARAMETERS);
    }
}
