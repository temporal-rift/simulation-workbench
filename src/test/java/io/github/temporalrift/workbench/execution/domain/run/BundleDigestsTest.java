package io.github.temporalrift.workbench.execution.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BundleDigestsTest {

    @Test
    void equalDigestsAreTheSameBundle() {
        assertThat(new BundleDigests("a".repeat(64), "b".repeat(64)))
                .isEqualTo(new BundleDigests("a".repeat(64), "b".repeat(64)))
                .isNotEqualTo(new BundleDigests("b".repeat(64), "a".repeat(64)));
    }

    @Test
    void rejectsMissingOrMalformedDigests() {
        var rules = "a".repeat(64);
        var content = "b".repeat(64);
        var uppercase = "B".repeat(64);
        var tooShort = "a".repeat(63);

        assertThatThrownBy(() -> new BundleDigests(null, content))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rulesDigest");
        assertThatThrownBy(() -> new BundleDigests(rules, uppercase))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentDigest");
        assertThatThrownBy(() -> new BundleDigests(tooShort, content)).isInstanceOf(IllegalArgumentException.class);
    }
}
