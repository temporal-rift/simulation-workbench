package io.github.temporalrift.workbench.execution.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;

class LaneFitTest {

    private static final String CONTENT = "b".repeat(64);
    private static final BundleProvenance.ServiceBuild BUILD =
            new BundleProvenance.ServiceBuild("sha256:" + "e".repeat(64), "abc1234");
    private static final BundleProvenance.ServiceBuild OTHER_BUILD =
            new BundleProvenance.ServiceBuild("sha256:" + "e".repeat(64), "abc1235");
    private static final Map<String, String> CONTRACTS = Map.of("session-api", "3.0.0", "action-api", "7.1.0");

    @Test
    void theSameEnvironmentHasTheSameFitWhateverTheOrderOfItsContracts() {
        var first = LaneFit.of(CONTENT, BUILD, BUILD, BUILD, CONTRACTS);
        var second = LaneFit.of(CONTENT, BUILD, BUILD, BUILD, Map.of("action-api", "7.1.0", "session-api", "3.0.0"));

        assertThat(first).isEqualTo(second);
        assertThat(first.digest()).matches("[a-f0-9]{64}");
    }

    @Test
    void anotherContentServiceBuildOrContractVersionFitsDifferently() {
        var fit = LaneFit.of(CONTENT, BUILD, BUILD, BUILD, CONTRACTS);

        assertThat(LaneFit.of("c".repeat(64), BUILD, BUILD, BUILD, CONTRACTS)).isNotEqualTo(fit);
        assertThat(LaneFit.of(CONTENT, OTHER_BUILD, BUILD, BUILD, CONTRACTS)).isNotEqualTo(fit);
        assertThat(LaneFit.of(CONTENT, BUILD, OTHER_BUILD, BUILD, CONTRACTS)).isNotEqualTo(fit);
        assertThat(LaneFit.of(CONTENT, BUILD, BUILD, OTHER_BUILD, CONTRACTS)).isNotEqualTo(fit);
        assertThat(LaneFit.of(CONTENT, BUILD, BUILD, BUILD, Map.of("session-api", "3.0.1", "action-api", "7.1.0")))
                .isNotEqualTo(fit);
        assertThat(LaneFit.of(CONTENT, BUILD, BUILD, BUILD, Map.of("session-api", "3.0.0")))
                .isNotEqualTo(fit);
    }

    @Test
    void theRulesAThePlayedBundleHoldsAreNotPartOfTheFit() {
        var provenance = new BundleProvenance(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                BUILD,
                BUILD,
                BUILD,
                new java.util.TreeMap<>(CONTRACTS));
        var other = new BundleProvenance(
                new BundleProvenance.Artifact("f".repeat(64), "other-rules"),
                new BundleProvenance.Artifact("d".repeat(64), null),
                BUILD,
                BUILD,
                BUILD,
                new java.util.TreeMap<>(CONTRACTS));

        assertThat(LaneFit.of(CONTENT, provenance)).isEqualTo(LaneFit.of(CONTENT, other));
    }

    @Test
    void aFitIsADigest() {
        assertThatThrownBy(() -> new LaneFit("not a digest")).isInstanceOf(IllegalArgumentException.class);
    }
}
