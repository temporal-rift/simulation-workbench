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
        assertThatThrownBy(() -> new BundleDigests(null, "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rulesDigest");
        assertThatThrownBy(() -> new BundleDigests("a".repeat(64), "B".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contentDigest");
        assertThatThrownBy(() -> new BundleDigests("a".repeat(63), "b".repeat(64)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
