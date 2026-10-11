package io.github.temporalrift.workbench.policy.application.query;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyReferenceVerdict;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;

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
        assertThat(catalog.list()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.description()).isNotBlank();
            assertThat(catalog.verify(entry.id(), entry.version(), entry.artifactDigest(), 0))
                    .isEqualTo(PolicyReferenceVerdict.VALID);
        });
    }

    @Test
    void unknownIdOrVersionIsUnknown() {
        var digest = BaselinePolicies.RANDOM_V1.artifactDigest();

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
        var bundle = BaselinePolicies.RANDOM_V1;

        assertThat(catalog.verify(bundle.id(), bundle.version(), bundle.artifactDigest(), 1))
                .isEqualTo(PolicyReferenceVerdict.UNSUPPORTED_PARAMETERS);
    }
}
